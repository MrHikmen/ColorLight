package me.mrhikmen.colorlight.client.compat.lod.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import me.mrhikmen.colorlight.client.compat.lod.LodLightStore;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The list of coloured light sources of the world, for Voxy's distant LODs.
 * <p>
 * <b>What the CPU does:</b> it only finds out <i>where</i> light sources are. Voxy's level-0 sections (full block
 * resolution, saved by Voxy itself) are scanned in the background for blocks the ColorLight registry knows as
 * coloured lights; every 32x32-block section column keeps a flat list of {@code x, y, z, rgb}. The lists are
 * remembered on disk, so nothing has to be read twice, and a column is read again when Voxy reports a change in it
 * (or the engine changed a nearby light), so a placed or broken light shows up (or goes away) by itself.
 * <p>
 * <b>What the GPU does:</b> {@link VoxyLightBridge} uploads those lists together with a small table "column -> where
 * its sources are", and {@code colorlight_lod.glsl} works out the colour of every LOD block itself, from the sources
 * around it (3D distance, linear fade over the light range - like the normal engine). The brightness still comes
 * from Voxy's own per-voxel light value, which is also what Voxy does for its normal lighting.
 * <p>
 * Everything here is safe to call from any thread. Voxy storage is only touched from one background worker, in
 * short time slices with pauses between, so it never takes a noticeable part of a frame.
 */
public final class VoxyLightCache {

    /** Cells (16x16 blocks) per side of the toroidal "cell -> sources" table (keep in sync with VoxyLightBridge and the shader). */
    public static final int GRID_CELLS = 512;
    /** Most sources kept per 16x16-block cell in the uploaded table (strongest first). */
    private static final int PER_CELL_CAP = 96;
    /** Most sources uploaded at once (nearest columns first). Keep in sync with VoxyLightBridge. */
    public static final int MAX_SOURCES = 131072;
    /** Radius, in 32-block columns, that is scanned and uploaded (2880 blocks). */
    public static final int WINDOW_COLS = 90;
    /** The camera may move this many columns before the table is rebuilt (the shader keeps this margin). */
    public static final int DRIFT_COLS = 8;

    private static final int MAX_PER_COLUMN = 512;
    private static final int SCAN_TOP_Y = 400;
    private static final int SCAN_BOTTOM_Y = -128;
    private static final int MIN_BRIGHTNESS = 8; // out of 255

    private static final long SLICE_NANOS = 3_000_000L;
    private static final long SLICE_GAP_MS = 40L;
    private static final long VOXY_CHANGE_DELAY_MS = 800L;
    private static final long ENGINE_CHANGE_DELAY_MS = 2_500L;
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

    /** The shared store of the world: column key (x,z of a level-0 section) -> flat {x, y, z, rgb} per source. */
    private final LodLightStore store;
    private final LodLightStore.Channel channel;
    private final ConcurrentHashMap<Long, int[]> columns;
    private final AtomicLong version;
    private final AtomicBoolean unsaved;
    /** Set by {@link #startImport}: the next pass reads every column again instead of skipping the known ones. */
    private volatile boolean forceAll;

    public VoxyLightCache(LodLightStore store) {
        this.store = store;
        this.channel = store.newChannel(WINDOW_COLS, PER_CELL_CAP);
        this.columns = store.columns;
        this.version = store.version;
        this.unsaved = store.unsaved;
    }

    public LodLightStore store() {
        return store;
    }

    private final ConcurrentHashMap<Long, Long> dirtyCols = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<long[]> incomingEngineDirty = new ConcurrentLinkedQueue<>();

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ColorLight Voxy Light Scanner");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile WorldEngine pendingEngine;
    private volatile int pendingCamX, pendingCamZ;
    private WorldEngine lastEngine;
    private boolean loggedFailure;

    // block id -> light colour memo (worker thread only; cleared when the engine changes or a rescan starts)
    private byte[] memoState = new byte[4096]; // 0 unknown, 1 not a light, 2 light
    private int[] memoRgb = new int[4096];
    private volatile boolean memoStale;

    // columns the saved file already knew: Voxy may have meshed their sections before the file was read
    private final java.util.ArrayDeque<Long> remeshBacklog = new java.util.ArrayDeque<>();
    private boolean backlogFilled;

    // sweep: scanning every column around the player once (nearest first)
    private volatile boolean sweepActive;
    private volatile int sweepIdx;
    private volatile int sweepCenterX = Integer.MIN_VALUE, sweepCenterZ;

    // ------------------------------------------------------------------------------------------------------
    // Status / control (main thread)
    // ------------------------------------------------------------------------------------------------------

    /** Changes whenever any source list changed. */
    public long version() {
        return version.get();
    }

    /** True while the first pass over the columns around the player is still running. */
    public boolean isImporting() {
        return sweepActive;
    }

    public boolean hasImported() {
        return !sweepActive && !columns.isEmpty();
    }

    public int importDone() {
        return sweepIdx;
    }

    public int importTotal() {
        return SWEEP_DX.length;
    }

    /** Forgets every list and reads all columns around the player again. */
    public void startImport() {
        forceAll = true; // the columns stay (other LOD mods share them); every one is read again and overwritten
        dirtyCols.clear();
        incomingEngineDirty.clear();
        memoStale = true;
        version.incrementAndGet();
        unsaved.set(true);
        restartSweep(pendingCamX, pendingCamZ);
        ensureScheduled(0L);
    }

    public void cancelImport() {
        sweepActive = false;
    }

    private void restartSweep(int camBlockX, int camBlockZ) {
        sweepCenterX = camBlockX >> 5;
        sweepCenterZ = camBlockZ >> 5;
        sweepIdx = 0;
        sweepActive = true;
    }

    /** Sections the engine changed (PosKey of 16-block sections). Cheap: the work happens on the worker. */
    public void markDirty(long[] sectionKeys) {
        incomingEngineDirty.add(sectionKeys);
        ensureScheduled(ENGINE_CHANGE_DELAY_MS + 50L);
    }

    /** Voxy has (re)built or queued a section; only level 0 matters here: its column has to be read again. */
    public void onVoxySection(long pos) {
        if (WorldEngine.getLevel(pos) != 0)
            return;
        dirtyCols.putIfAbsent(colKey(WorldEngine.getX(pos), WorldEngine.getZ(pos)), System.currentTimeMillis() + VOXY_CHANGE_DELAY_MS);
        ensureScheduled(VOXY_CHANGE_DELAY_MS + 50L);
    }

    /**
     * Called once a client tick. Only remembers Voxy's engine for the current dimension and the camera position,
     * and keeps the first pass going; scans nothing itself.
     */
    public void tick(ClientLevel level) {
        if (level == null || !(level instanceof IWorldGetIdentifier getter))
            return;
        WorldIdentifier id = getter.voxy$getIdentifier();
        if (id == null)
            return;
        var instance = VoxyCommon.getInstance();
        if (instance == null)
            return;
        if (!store.loaded())
            return; // the saved light is still being read
        WorldEngine engine = instance.getNullable(id);
        if (engine == null || !engine.isLive())
            return;
        var player = Minecraft.getInstance().player;
        if (player == null)
            return;
        if (!backlogFilled) {
            backlogFilled = true;
            synchronized (remeshBacklog) {
                int pcx = player.getBlockX() >> 5, pcz = player.getBlockZ() >> 5;
                for (Map.Entry<Long, int[]> e : columns.entrySet()) {
                    long k = e.getKey();
                    if (e.getValue().length > 0 && Math.abs((int) (k >> 32) - pcx) <= WINDOW_COLS && Math.abs((int) k - pcz) <= WINDOW_COLS)
                        remeshBacklog.add(k);
                }
            }
            if (!remeshBacklog.isEmpty())
                ensureScheduled(1_000L);
        }
        pendingEngine = engine;
        pendingCamX = player.getBlockX();
        pendingCamZ = player.getBlockZ();

        int camCx = pendingCamX >> 5, camCz = pendingCamZ >> 5;
        if (!sweepActive && (sweepCenterX == Integer.MIN_VALUE
                || Math.abs(camCx - sweepCenterX) >= 4 || Math.abs(camCz - sweepCenterZ) >= 4))
            restartSweep(pendingCamX, pendingCamZ);
        if (sweepActive)
            ensureScheduled(0L);
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
        WorldEngine engine = pendingEngine;
        if (engine == null || !engine.isLive())
            return; // the next tick() asks again
        boolean more = false;
        try {
            if (engine != lastEngine || memoStale) {
                lastEngine = engine;
                memoStale = false;
                Arrays.fill(memoState, (byte) 0);
            }
            long deadline = System.nanoTime() + SLICE_NANOS;
            more = runDirty(engine, deadline);
            more |= runRemeshBacklog(engine, deadline);
            if (sweepActive)
                more |= runSweep(engine, deadline);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                ColorLightClient.LOGGER.warn("[ColorLight] Reading far light from Voxy failed (further failures are not logged)", t);
            }
        }
        if (more)
            ensureScheduled(SLICE_GAP_MS);
    }

    private boolean runRemeshBacklog(WorldEngine engine, long deadline) {
        while (true) {
            Long key;
            synchronized (remeshBacklog) {
                key = remeshBacklog.poll();
            }
            if (key == null)
                return false;
            int[] src = columns.get(key);
            if (src != null && src.length > 0)
                remesh(engine, null, src, true);
            if (System.nanoTime() > deadline)
                synchronized (remeshBacklog) {
                    return !remeshBacklog.isEmpty();
                }
        }
    }

    private boolean runSweep(WorldEngine engine, long deadline) {
        int cx0 = sweepCenterX, cz0 = sweepCenterZ;
        while (sweepActive && sweepIdx < SWEEP_DX.length) {
            int i = sweepIdx;
            int cx = cx0 + SWEEP_DX[i];
            int cz = cz0 + SWEEP_DZ[i];
            sweepIdx = i + 1;
            if (forceAll || !columns.containsKey(colKey(cx, cz))) {
                scanAndStore(engine, cx, cz);
                if (System.nanoTime() > deadline)
                    return sweepIdx < SWEEP_DX.length;
            }
        }
        sweepActive = false;
        forceAll = false;
        return false;
    }

    private boolean runDirty(WorldEngine engine, long deadline) {
        long now = System.currentTimeMillis();
        long[] keys;
        while ((keys = incomingEngineDirty.poll()) != null) {
            for (long key : keys) {
                // engine sections are 16 blocks wide; a column is 32
                dirtyCols.putIfAbsent(colKey(PosKey.x(key) >> 1, PosKey.z(key) >> 1), now + ENGINE_CHANGE_DELAY_MS);
            }
        }

        boolean waiting = false;
        Iterator<Map.Entry<Long, Long>> it = dirtyCols.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (e.getValue() > now) {
                waiting = true;
                continue;
            }
            long k = e.getKey();
            it.remove();
            scanAndStore(engine, (int) (k >> 32), (int) k);
            if (System.nanoTime() > deadline)
                return true;
        }
        return waiting;
    }

    private void scanAndStore(WorldEngine engine, int cx, int cz) {
        int[] fresh = scanColumn(engine, cx, cz);
        int[] old = columns.put(colKey(cx, cz), fresh);
        boolean all = forceAll;
        if (old == null || !Arrays.equals(old, fresh)) {
            version.incrementAndGet();
            unsaved.set(true);
            remesh(engine, old, fresh, all);
        } else if (all && fresh.length > 0) {
            remesh(engine, old, fresh, true);
        }
    }

    /** Voxy builds the sections near changed sources again: its quads are split around lights (see LodSplit). */
    private void remesh(WorldEngine engine, int[] old, int[] fresh, boolean all) {
        try {
            VoxyRemesh.request(engine, old, fresh, all);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                ColorLightClient.LOGGER.warn("[ColorLight] Asking Voxy to rebuild sections near lights failed (further failures are not logged)", t);
            }
        }
    }

    /** Reads one level-0 section column (all heights) and lists its coloured light blocks. */
    private int[] scanColumn(WorldEngine engine, int sx, int sz) {
        Map<Long, int[]> found = null; // dedupe key (4-block cell) -> {x, y, z, rgb, brightness}

        int syTop = Math.floorDiv(SCAN_TOP_Y, 32);
        int syBottom = Math.floorDiv(SCAN_BOTTOM_Y, 32);
        for (int sy = syTop; sy >= syBottom; sy--) {
            WorldSection section = engine.acquireIfExists(0, sx, sy, sz);
            if (section == null)
                continue;
            try {
                long[] data = section._unsafeGetRawDataArray();
                for (int lz = 0; lz < 32; lz++) {
                    for (int ly = 0; ly < 32; ly++) {
                        for (int lx = 0; lx < 32; lx++) {
                            long id = data[WorldSection.getIndex(lx, ly, lz)];
                            if (Mapper.isAir(id))
                                continue;
                            int rgb = emitterRgb(engine, Mapper.getBlockId(id));
                            if (rgb == 0)
                                continue;
                            // a light buried inside other blocks (the inside of a lava sea) lights nothing visible
                            if (lx > 0 && lx < 31 && ly > 0 && ly < 31 && lz > 0 && lz < 31
                                    && !Mapper.isAir(data[WorldSection.getIndex(lx - 1, ly, lz)]) && !Mapper.isAir(data[WorldSection.getIndex(lx + 1, ly, lz)])
                                    && !Mapper.isAir(data[WorldSection.getIndex(lx, ly - 1, lz)]) && !Mapper.isAir(data[WorldSection.getIndex(lx, ly + 1, lz)])
                                    && !Mapper.isAir(data[WorldSection.getIndex(lx, ly, lz - 1)]) && !Mapper.isAir(data[WorldSection.getIndex(lx, ly, lz + 1)]))
                                continue;
                            int x = sx * 32 + lx, y = sy * 32 + ly, z = sz * 32 + lz;
                            int brightness = Math.max(ColorLightUtil.r(rgb), Math.max(ColorLightUtil.g(rgb), ColorLightUtil.b(rgb)));
                            long key = (((long) (x >> 2)) & 0x3FFFFFL) << 42 | (((long) (z >> 2)) & 0x3FFFFFL) << 20 | (((long) ((y >> 2) + 4096)) & 0xFFFFFL);
                            if (found == null)
                                found = new HashMap<>();
                            int[] prev = found.get(key);
                            if (prev == null || prev[4] < brightness)
                                found.put(key, new int[]{x, y, z, rgb, brightness});
                        }
                    }
                }
            } finally {
                section.release();
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
    private int emitterRgb(WorldEngine engine, int blockId) {
        if (blockId >= memoState.length) {
            int n = Integer.highestOneBit(blockId) << 1;
            memoState = Arrays.copyOf(memoState, n);
            memoRgb = Arrays.copyOf(memoRgb, n);
        }
        byte st = memoState[blockId];
        if (st == 1)
            return 0;
        if (st == 2)
            return memoRgb[blockId];

        int result = 0;
        BlockState state = engine.getMapper().getBlockStateFromBlockId(blockId);
        if (state != null) {
            int emission = state.getLightEmission();
            BlockSettings settings = emission > 0 ? ColorLightBlockRegistry.get(state.getBlock()) : null;
            if (settings != null) {
                int strength = Math.min(settings.light, emission);
                float scale = Math.max(0f, Math.min(1f, strength / 15f));
                int rgb = ColorLightUtil.pack(Math.round(settings.r * scale), Math.round(settings.g * scale), Math.round(settings.b * scale)) & 0xFFFFFF;
                int brightness = Math.max(ColorLightUtil.r(rgb), Math.max(ColorLightUtil.g(rgb), ColorLightUtil.b(rgb)));
                if (brightness >= MIN_BRIGHTNESS) {
                    // top byte: this block's own reach (bits 0..4) and the table of its propagation method (bits 5..7), see LodMethodTables
                    int shape = me.mrhikmen.colorlight.client.compat.lod.LodMethodTables.slotOf(settings.getPropagationId());
                    result = rgb | ((Math.max(1, Math.min(16, strength)) | (shape << 5)) << 24);
                }
            }
        }
        memoState[blockId] = (byte) (result == 0 ? 1 : 2);
        memoRgb[blockId] = result;
        return result;
    }

    private static long colKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void shutdown() {
        sweepActive = false;
        worker.shutdownNow();
    }
}
