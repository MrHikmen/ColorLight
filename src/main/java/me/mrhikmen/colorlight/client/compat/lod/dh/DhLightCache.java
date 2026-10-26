package me.mrhikmen.colorlight.client.compat.lod.dh;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.lod.LodLightStore;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The list of coloured light sources of the world, for Distant Horizons' LOD terrain.
 * <p>
 * <b>What the CPU does:</b> it only finds out <i>where</i> light sources are. Every 32x32-block column (four DH
 * chunks) is read through the public {@code DhApi} terrain data repo - the same stored LOD data DH itself renders
 * from, so this reaches as far as DH's own data goes, not just the client's render distance - and scanned for
 * blocks the ColorLight registry knows as coloured lights. The lists are remembered on disk, so nothing has to be
 * read twice.
 * <p>
 * Unlike the Voxy compat, there is no "a section changed" hint available through the public API: a finished sweep
 * restarts on its own every {@link #RESCAN_INTERVAL_MS}, so a newly placed or broken light shows up (or goes away)
 * within that interval instead of immediately.
 * <p>
 * <b>What the GPU does:</b> {@link DhLightBridge} uploads those lists together with a small table
 * "column -> where its sources are", and {@code colorlight_dh.glsl} works out the colour of every fragment itself,
 * from the sources around it (3D distance, linear fade over the light range - like the normal engine). The
 * brightness still comes from DH's own light level for that fragment.
 * <p>
 * Everything here is safe to call from any thread.
 */
public final class DhLightCache {

    /** 16x16-block cells per side of the toroidal "cell -> sources" table (keep in sync with DhLightBridge and the shader). */
    public static final int GRID_CELLS = 512;
    /** Most sources kept per cell (the shader reads at most this many per cell). */
    private static final int PER_CELL_CAP = 32;
    /** Most sources uploaded at once (nearest columns first). Keep in sync with DhLightBridge. */
    public static final int MAX_SOURCES = 131072;
    /**
     * Radius, in 32-block columns, that is scanned and uploaded (1024 blocks). Deliberately much smaller than the
     * Voxy compat's window: each column here costs 4 real queries against Distant Horizons' SQLite terrain data
     * (DhApi.Delayed.terrainRepo), not a lookup in an in-memory structure, so a bigger radius means a full sweep
     * that may never realistically finish (90 columns is 32761 of them, i.e. over 131000 queries). Keep this and
     * the matching literal in colorlight_dh.glsl (currently WINDOW_COLS - DRIFT_COLS) in sync.
     */
    public static final int WINDOW_COLS = 32;
    /** The camera may move this many columns before the table is rebuilt (the shader keeps this margin). */
    public static final int DRIFT_COLS = 8;

    private static final int MAX_PER_COLUMN = 128;
    private static final int MIN_BRIGHTNESS = 8; // out of 255

    private static final long SLICE_NANOS = 8_000_000L;
    private static final long SLICE_GAP_MS = 40L;
    /** No "section changed" hint exists through the public API: a full sweep is simply repeated on this cadence. */
    private static final long RESCAN_INTERVAL_MS = 300_000L;
    private static final int[] NO_SOURCES = new int[0];

    /** Nearest-first list of column offsets inside {@link #WINDOW_COLS}. */
    private static final int[] SWEEP_DX;
    private static final int[] SWEEP_DZ;

    static {
        int r = WINDOW_COLS;
        List<long[]> all = new ArrayList<>();
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                all.add(new long[]{(long) dx * dx + (long) dz * dz, dx, dz});
        all.sort((a, b) -> Long.compare(a[0], b[0]));
        SWEEP_DX = new int[all.size()];
        SWEEP_DZ = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            SWEEP_DX[i] = (int) all.get(i)[1];
            SWEEP_DZ[i] = (int) all.get(i)[2];
        }
    }

    /** The shared store of the world: 32-block column key (x,z) -> flat {x, y, z, rgb} per source. */
    private final LodLightStore store;
    private final LodLightStore.Channel channel;
    private final ConcurrentHashMap<Long, int[]> columns;
    private final AtomicLong version;
    private final AtomicBoolean unsaved;
    /** The next pass reads every column again instead of skipping the ones the store already knows. */
    private volatile boolean forceAll;

    public DhLightCache(LodLightStore store) {
        this.store = store;
        this.channel = store.newChannel(WINDOW_COLS, PER_CELL_CAP);
        this.columns = store.columns;
        this.version = store.version;
        this.unsaved = store.unsaved;
    }

    public LodLightStore store() {
        return store;
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ColorLight DH Light Scanner");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile IDhApiLevelWrapper pendingLevel;
    private volatile int pendingCamX, pendingCamZ;
    private boolean loggedFailure;
    private boolean eventsBound;
    /** 32-block column key -> time (ms) from which it is read again (DH reported one of its chunks as modified). */
    private final ConcurrentHashMap<Long, Long> dirtyCols = new ConcurrentHashMap<>();
    private static final long CHUNK_CHANGE_DELAY_MS = 4_000L;

    private IDhApiTerrainDataCache dataCache;
    /** The cache keeps loaded DH data; a new sweep or a changed chunk needs fresh data, so it is dropped first. */
    private volatile boolean dropCache;

    // block -> light colour memo (worker thread only; cleared when the level changes)
    private final Map<BlockState, Integer> memo = new java.util.IdentityHashMap<>();
    private IDhApiLevelWrapper memoLevel;

    // sweep: scanning every column around the player once (nearest first), then again every RESCAN_INTERVAL_MS
    private volatile boolean sweepActive;
    private volatile int sweepIdx;
    private volatile int sweepCenterX = Integer.MIN_VALUE, sweepCenterZ;
    private volatile long nextRescanMs;
    private volatile boolean everCompletedSweep;

    /** Total columns a full sweep visits (progress denominator). */
    public int scanTotal() {
        return SWEEP_DX.length;
    }

    /** Columns visited in the sweep currently running, or the last one if none is running right now. */
    public int scanDone() {
        return sweepIdx;
    }

    /** True while a sweep (initial or periodic) is actively reading chunks right now. */
    public boolean isScanning() {
        return sweepActive;
    }

    /** True once at least one full sweep around some position has finished. */
    public boolean hasScannedOnce() {
        return everCompletedSweep;
    }

    public void cancelScan() {
        sweepActive = false;
        forceAll = false;
    }

    /** Starts a sweep centred on the given position right now, even if one is already running or it is not due yet. */
    public void forceRescan(int camBlockX, int camBlockZ) {
        forceAll = true;
        restartSweep(camBlockX, camBlockZ);
    }

    // ------------------------------------------------------------------------------------------------------
    // Status / control (main thread)
    // ------------------------------------------------------------------------------------------------------

    /** Changes whenever any source list changed. */
    public long version() {
        return version.get();
    }

    /** DH changed a chunk: its 32-block column is read again soon (DH writes the new data asynchronously). */
    private void markChunk(int chunkX, int chunkZ) {
        dirtyCols.putIfAbsent(colKey(chunkX >> 1, chunkZ >> 1), System.currentTimeMillis() + CHUNK_CHANGE_DELAY_MS);
        dropCache = true;
        ensureScheduled(CHUNK_CHANGE_DELAY_MS + 50L);
    }

    private void restartSweep(int camBlockX, int camBlockZ) {
        sweepCenterX = camBlockX >> 5;
        sweepCenterZ = camBlockZ >> 5;
        sweepIdx = 0;
        sweepActive = true;
        dropCache = true;
    }

    /**
     * Called once a client tick. Remembers the current dimension's DH level and the camera position, and keeps the
     * sweep going; scans nothing itself.
     */
    public void tick(ClientLevel level) {
        if (level == null)
            return;
        if (!eventsBound) {
            eventsBound = true;
            try {
                com.seibel.distanthorizons.api.methods.events.DhApiEventRegister.on(
                        com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent.class,
                        new com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent() {
                            @Override
                            public void onChunkModified(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam<EventParam> input) {
                                DhLightCache.this.markChunk(input.value.chunkX, input.value.chunkZ);
                            }
                        });
            } catch (Throwable t) {
                ColorLightClient.LOGGER.warn("[ColorLight] Could not listen to Distant Horizons chunk changes: {}", t.toString());
            }
        }
        if (!store.loaded())
            return; // the saved light is still being read
        IDhApiLevelWrapper found = findLevelWrapper(level);
        if (found == null)
            return;

        var player = Minecraft.getInstance().player;
        if (player == null)
            return;
        pendingLevel = found;
        pendingCamX = player.getBlockX();
        pendingCamZ = player.getBlockZ();

        int camCx = pendingCamX >> 5, camCz = pendingCamZ >> 5;
        long now = System.currentTimeMillis();
        if (!sweepActive && (sweepCenterX == Integer.MIN_VALUE
                || Math.abs(camCx - sweepCenterX) >= 4 || Math.abs(camCz - sweepCenterZ) >= 4
                || now >= nextRescanMs)) {
            if (now >= nextRescanMs && sweepCenterX != Integer.MIN_VALUE)
                forceAll = true; // the slow refresh: everything is read again
            restartSweep(pendingCamX, pendingCamZ);
        }
        if (sweepActive)
            ensureScheduled(0L);
    }

    /**
     * DH's wrapper of the dimension the player is in. In singleplayer DH's level wrapper wraps the integrated
     * server's level, not the {@code ClientLevel}, so the match is by dimension, not by object identity.
     */
    private static IDhApiLevelWrapper findLevelWrapper(ClientLevel level) {
        var proxy = DhApi.Delayed.worldProxy;
        if (proxy == null)
            return null;
        try {
            if (!proxy.worldLoaded())
                return null;
            for (IDhApiLevelWrapper w : proxy.getAllLoadedLevelWrappers()) {
                Object mc = w.getWrappedMcObject();
                if (mc == level)
                    return w;
                if (mc instanceof net.minecraft.world.level.Level l && l.dimension().equals(level.dimension()))
                    return w;
            }
            return proxy.getSinglePlayerLevel();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // GPU table: built by the shared store's channel for this mod's shader
    // ------------------------------------------------------------------------------------------------------

    public void requestFill(int camBlockX, int camBlockZ) {
        channel.requestFill(camBlockX, camBlockZ);
    }

    public boolean pollFilled(long[] stamp) {
        return channel.pollFilled(stamp);
    }

    public java.nio.IntBuffer cellBuffer() {
        return channel.cellBuffer();
    }

    public java.nio.IntBuffer sourceBuffer(int count) {
        return channel.sourceBuffer(count);
    }

    public java.nio.IntBuffer coarseBuffer() {
        return channel.coarseBuffer();
    }

    public void releaseFilled() {
        channel.releaseFilled();
    }

    // ------------------------------------------------------------------------------------------------------
    // Background worker
    // ------------------------------------------------------------------------------------------------------

    private void ensureScheduled(long delayMs) {
        if (scheduled.compareAndSet(false, true)) {
            try {
                worker.schedule(this::slice, delayMs, TimeUnit.MILLISECONDS);
            } catch (RuntimeException shutDown) {
                scheduled.set(false);
            }
        }
    }

    private void slice() {
        scheduled.set(false);
        IDhApiLevelWrapper level = pendingLevel;
        if (level == null)
            return; // the next tick() asks again
        boolean more = false;
        try {
            if (level != memoLevel) {
                memoLevel = level;
                memo.clear();
            }
            if (dropCache) {
                dropCache = false;
                if (dataCache != null) {
                    try {
                        dataCache.close();
                    } catch (Throwable ignored) {
                    }
                    dataCache = null;
                }
            }
            if (dataCache == null && DhApi.Delayed.terrainRepo != null)
                dataCache = DhApi.Delayed.terrainRepo.createSoftCache();

            long deadline = System.nanoTime() + SLICE_NANOS;
            more = runDirty(level, deadline);
            if (sweepActive)
                more |= runSweep(level, deadline);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                ColorLightClient.LOGGER.warn("[ColorLight] Reading far light from Distant Horizons failed (further failures are not logged)", t);
            }
        }
        if (more)
            ensureScheduled(SLICE_GAP_MS);
    }

    private boolean runDirty(IDhApiLevelWrapper level, long deadline) {
        long now = System.currentTimeMillis();
        boolean waiting = false;
        var it = dirtyCols.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue() > now) {
                waiting = true;
                continue;
            }
            long k = e.getKey();
            it.remove();
            scanAndStore(level, (int) (k >> 32), (int) k);
            if (System.nanoTime() > deadline)
                return true;
        }
        return waiting;
    }

    private boolean runSweep(IDhApiLevelWrapper level, long deadline) {
        int cx0 = sweepCenterX, cz0 = sweepCenterZ;
        while (sweepActive && sweepIdx < SWEEP_DX.length) {
            int i = sweepIdx;
            int cx = cx0 + SWEEP_DX[i];
            int cz = cz0 + SWEEP_DZ[i];
            sweepIdx = i + 1;
            if (forceAll || !columns.containsKey(colKey(cx, cz)))
                scanAndStore(level, cx, cz);
            if (System.nanoTime() > deadline)
                return sweepIdx < SWEEP_DX.length;
        }
        sweepActive = false;
        everCompletedSweep = true;
        forceAll = false;
        nextRescanMs = System.currentTimeMillis() + RESCAN_INTERVAL_MS;
        return false;
    }

    private void scanAndStore(IDhApiLevelWrapper level, int cx, int cz) {
        int[] fresh = scanColumn(level, cx, cz);
        if (fresh == null)
            return; // DH doesn't have this column's data yet: left unscanned, tried again on the next sweep
        int[] old = columns.put(colKey(cx, cz), fresh);
        if (old == null || !Arrays.equals(old, fresh)) {
            version.incrementAndGet();
            unsaved.set(true);
        }
    }

    /** Reads one 32x32-block column (four DH chunks) and lists its coloured light blocks, or null if not ready yet. */
    private int[] scanColumn(IDhApiLevelWrapper level, int sx, int sz) {
        Map<Long, int[]> found = null; // dedupe key (4-block cell) -> {x, y, z, rgb, brightness}

        for (int dcz = 0; dcz < 2; dcz++) {
            for (int dcx = 0; dcx < 2; dcx++) {
                int chunkX = sx * 2 + dcx, chunkZ = sz * 2 + dcz;
                DhApiResult<DhApiTerrainDataPoint[][][]> result =
                        DhApi.Delayed.terrainRepo.getAllTerrainDataAtChunkPos(level, chunkX, chunkZ, dataCache);
                if (!result.success)
                    return null; // not generated / not loaded by DH yet
                DhApiTerrainDataPoint[][][] cols = result.payload;
                if (cols == null)
                    continue;

                for (int rx = 0; rx < cols.length; rx++) {
                    DhApiTerrainDataPoint[][] row = cols[rx];
                    if (row == null)
                        continue;
                    for (int rz = 0; rz < row.length; rz++) {
                        DhApiTerrainDataPoint[] points = row[rz];
                        if (points == null)
                            continue;
                        for (DhApiTerrainDataPoint point : points) {
                            if (point == null || point.blockStateWrapper == null || point.blockStateWrapper.isAir())
                                continue;
                            int rgb = emitterRgb(point.blockStateWrapper);
                            if (rgb == 0)
                                continue;

                            int x = chunkX * 16 + rx, z = chunkZ * 16 + rz;
                            int brightness = Math.max(ColorLightUtil.r(rgb), Math.max(ColorLightUtil.g(rgb), ColorLightUtil.b(rgb)));
                            // a data point is a run of one block over a height range (already absolute heights, top
                            // exclusive): only the ends of a run can be exposed, the inside lights nothing visible
                            int top = point.topYBlockPos - 1;
                            int bottom = point.bottomYBlockPos;
                            for (int n = 0; n < 2; n++) {
                                if (n == 1 && bottom >= top)
                                    break;
                                int y = (n == 0) ? top : bottom;
                                long key = (((long) (x >> 2)) & 0x3FFFFFL) << 42 | (((long) (z >> 2)) & 0x3FFFFFL) << 20 | (((long) ((y >> 2) + 4096)) & 0xFFFFFL);
                                if (found == null)
                                    found = new HashMap<>();
                                int[] prev = found.get(key);
                                if (prev == null || prev[4] < brightness)
                                    found.put(key, new int[]{x, y, z, rgb, brightness});
                            }
                        }
                    }
                }
            }
        }

        if (found == null || found.isEmpty())
            return NO_SOURCES;

        List<int[]> list = new ArrayList<>(found.values());
        if (list.size() > MAX_PER_COLUMN) {
            list.sort((a, b) -> Integer.compare(b[4], a[4]));
            list = list.subList(0, MAX_PER_COLUMN);
        }
        int[] out = new int[list.size() * 4];
        for (int i = 0; i < list.size(); i++) {
            int[] s = list.get(i);
            out[i * 4] = s[0];
            out[i * 4 + 1] = s[1];
            out[i * 4 + 2] = s[2];
            out[i * 4 + 3] = s[3];
        }
        return out;
    }

    /** {@code 0x00BBGGRR} (scaled by the light's strength) if the block is a coloured light source, else 0. Memoised. */
    private int emitterRgb(com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper wrapper) {
        Object wrapped = wrapper.getWrappedMcObject();
        if (!(wrapped instanceof BlockState state))
            return 0;
        Block block = state.getBlock();

        Integer memoised = memo.get(state);
        if (memoised != null)
            return memoised;

        int result = 0;
        int emission = state.getLightEmission();
        if (emission > 0) {
            BlockSettings settings = ColorLightBlockRegistry.get(block);
            if (settings != null) {
                int strength = Math.min(settings.light, emission);
                float scale = Math.max(0f, Math.min(1f, strength / 15f));
                int rgb = ColorLightUtil.pack(Math.round(settings.r * scale), Math.round(settings.g * scale), Math.round(settings.b * scale)) & 0xFFFFFF;
                int brightness = Math.max(ColorLightUtil.r(rgb), Math.max(ColorLightUtil.g(rgb), ColorLightUtil.b(rgb)));
                if (brightness >= MIN_BRIGHTNESS)
                    result = rgb | (Math.max(1, Math.min(16, strength)) << 24); // this block's own reach
            }
        }
        memo.put(state, result);
        return result;
    }

    private static long colKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void shutdown() {
        sweepActive = false;
        worker.shutdownNow();
        if (dataCache != null) {
            try {
                dataCache.close();
            } catch (Throwable ignored) {
            }
            dataCache = null;
        }
    }
}
