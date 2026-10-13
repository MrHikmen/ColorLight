package me.mrhikmen.colorlight.client.compat.distanthorizons;

import me.mrhikmen.colorlight.client.ColorLightClient;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
    private static final long RESCAN_INTERVAL_MS = 20_000L;
    private static final int[] NO_SOURCES = new int[0];

    private static final int FILE_MAGIC = 0x434C4448; // 'CLDH'
    private static final int FILE_FORMAT = 1;

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

    /** 32-block column key (x,z) -> flat {x, y, z, rgb} per source. Present (even empty) = scanned. */
    private final ConcurrentHashMap<Long, int[]> columns = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private final AtomicBoolean unsaved = new AtomicBoolean();

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ColorLight DH Light Scanner");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final ExecutorService filler = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ColorLight DH Light Table");
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
    private final AtomicInteger sourceCount = new AtomicInteger();

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

    /** Coloured light sources currently known, across every column read so far (not just the uploaded window). */
    public int sourcesFound() {
        return sourceCount.get();
    }

    /** Starts a sweep centred on the given position right now, even if one is already running or it is not due yet. */
    public void forceRescan(int camBlockX, int camBlockZ) {
        restartSweep(camBlockX, camBlockZ);
    }

    // GPU table
    private static final int FILL_IDLE = 0, FILL_REQUESTED = 1, FILL_READY = 2;
    private final AtomicInteger fillState = new AtomicInteger(FILL_IDLE);
    private final long[] fillStamp = new long[4];
    private IntBuffer cellBuf;
    private IntBuffer coarseBuf;
    private IntBuffer srcBuf;

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
    // GPU table (render thread asks, filler thread builds, render thread uploads)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Asks the filler thread to build the "column -> sources" table and the source list around the camera (nearest
     * columns first). Does nothing while one is being built or waits to be picked up. Cheap.
     */
    public void requestFill(int camBlockX, int camBlockZ) {
        if (!fillState.compareAndSet(FILL_IDLE, FILL_REQUESTED))
            return;
        long v = version.get();
        try {
            filler.execute(() -> {
                try {
                    build(v, camBlockX >> 5, camBlockZ >> 5);
                    fillState.set(FILL_READY);
                } catch (Throwable t) {
                    fillState.set(FILL_IDLE);
                }
            });
        } catch (RuntimeException shutDown) {
            fillState.set(FILL_IDLE);
        }
    }

    /**
     * @return true if a finished table is waiting; {@code stamp} gets {version, camCol x, camCol z, source count}.
     *         Read {@link #cellBuffer} / {@link #sourceBuffer}, then call {@link #releaseFilled}.
     */
    public boolean pollFilled(long[] stamp) {
        if (fillState.get() != FILL_READY)
            return false;
        System.arraycopy(fillStamp, 0, stamp, 0, 4);
        return true;
    }

    /** Ints per side of the coarse table (32x32-block cells); keep in sync with DhLightBridge and the shader. */
    public static final int COARSE_SIDE = 256;

    /** Coarse flags: 1 where a source is within one 32-block cell, else 0 ({@link #COARSE_SIDE} squared ints). */
    public IntBuffer coarseBuffer() {
        coarseBuf.clear();
        return coarseBuf;
    }

    public IntBuffer cellBuffer() {
        cellBuf.clear();
        return cellBuf;
    }

    /** Positioned at 0 with its limit at {@code count * 4} ints. */
    public IntBuffer sourceBuffer(int count) {
        srcBuf.clear();
        srcBuf.limit(count * 4);
        return srcBuf;
    }

    public void releaseFilled() {
        fillState.set(FILL_IDLE);
    }

    private int[] cellCount;
    private int[] cellStart;
    private int[] cellFill;

    /**
     * Regroups the per-column lists of the columns around the camera (nearest first) into 16x16-block cells: the
     * shader looks at the 3x3 cells around a block, so a cell holds few sources even over a lava sea or a lamp
     * field. Layout: cell table (first source, count) per cell, then the sources of all cells one after the other.
     */
    private void build(long v, int camCx, int camCz) {
        int cellTotal = GRID_CELLS * GRID_CELLS;
        if (cellBuf == null) {
            coarseBuf = ByteBuffer.allocateDirect(COARSE_SIDE * COARSE_SIDE * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
            cellBuf = ByteBuffer.allocateDirect(cellTotal * 2 * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
            srcBuf = ByteBuffer.allocateDirect(MAX_SOURCES * 4 * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
            cellCount = new int[cellTotal];
            cellStart = new int[cellTotal];
            cellFill = new int[cellTotal];
        }
        cellBuf.clear();
        srcBuf.clear();
        Arrays.fill(cellCount, 0);

        // pass 1: how many sources each cell gets (nearest columns first, per-cell and total caps)
        int total = 0;
        int lastColumn = SWEEP_DX.length;
        for (int i = 0; i < SWEEP_DX.length && total < MAX_SOURCES; i++) {
            int[] src = columns.get(colKey(camCx + SWEEP_DX[i], camCz + SWEEP_DZ[i]));
            if (src == null)
                continue;
            for (int j = 0; j < src.length && total < MAX_SOURCES; j += 4) {
                int cell = cellIndex(src[j], src[j + 2]);
                if (cellCount[cell] < PER_CELL_CAP) {
                    cellCount[cell]++;
                    total++;
                }
            }
            lastColumn = i;
        }

        // prefix sums -> table
        int running = 0;
        for (int c = 0; c < cellTotal; c++) {
            cellStart[c] = running;
            cellFill[c] = 0;
            cellBuf.put(c * 2, running);
            cellBuf.put(c * 2 + 1, cellCount[c]);
            running += cellCount[c];
        }

        // pass 2: the same walk, now writing the sources
        for (int i = 0; i <= lastColumn && i < SWEEP_DX.length; i++) {
            int[] src = columns.get(colKey(camCx + SWEEP_DX[i], camCz + SWEEP_DZ[i]));
            if (src == null)
                continue;
            for (int j = 0; j < src.length; j += 4) {
                int cell = cellIndex(src[j], src[j + 2]);
                if (cellFill[cell] >= cellCount[cell])
                    continue;
                int at = (cellStart[cell] + cellFill[cell]++) * 4;
                srcBuf.put(at, src[j]);
                srcBuf.put(at + 1, src[j + 1]);
                srcBuf.put(at + 2, src[j + 2]);
                srcBuf.put(at + 3, src[j + 3]);
            }
        }
        // coarse flags: a 32-block cell is flagged if a source lies in it or in one of its 8 neighbours, so the vertex
        // shader needs a single read to know whether the fragment shader has anything to do near it
        for (int i = 0; i < COARSE_SIDE * COARSE_SIDE; i++)
            coarseBuf.put(i, 0);
        for (int i = 0; i < SWEEP_DX.length && i <= lastColumn; i++) {
            int cx = camCx + SWEEP_DX[i], cz = camCz + SWEEP_DZ[i];
            int[] src = columns.get(colKey(cx, cz));
            if (src == null || src.length == 0)
                continue;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++)
                    coarseBuf.put(((cx + dx) & (COARSE_SIDE - 1)) | (((cz + dz) & (COARSE_SIDE - 1)) * COARSE_SIDE), 1);
        }
        fillStamp[0] = v;
        fillStamp[1] = camCx;
        fillStamp[2] = camCz;
        fillStamp[3] = running;
    }

    private static int cellIndex(int x, int z) {
        return ((x >> 4) & (GRID_CELLS - 1)) | (((z >> 4) & (GRID_CELLS - 1)) * GRID_CELLS);
    }
    // ------------------------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------------------------

    public boolean hasUnsavedChanges() {
        return unsaved.get();
    }

    /** Writes every column's list (gzip, atomic replace). Safe from any thread. */
    public void save(Path file) throws IOException {
        unsaved.set(false); // cleared first: a change made while writing marks it again
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Object[] entries = columns.entrySet().toArray();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(FILE_FORMAT);
                out.writeInt(entries.length);
                for (Object o : entries) {
                    @SuppressWarnings("unchecked")
                    Map.Entry<Long, int[]> e = (Map.Entry<Long, int[]>) o;
                    int[] a = e.getValue();
                    out.writeLong(e.getKey());
                    out.writeInt(a.length);
                    for (int x : a)
                        out.writeInt(x);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            unsaved.set(true);
            throw e;
        }
    }

    /** Adds the columns of a saved file (columns already read this session win). */
    public void load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_FORMAT)
                return;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long key = in.readLong();
                int len = in.readInt();
                if (len < 0 || len > MAX_PER_COLUMN * 4 || (len & 3) != 0)
                    throw new IOException("bad column");
                int[] a = len == 0 ? NO_SOURCES : new int[len];
                for (int j = 0; j < len; j++)
                    a[j] = in.readInt();
                if (columns.putIfAbsent(key, a) == null)
                    sourceCount.addAndGet(a.length / 4);
            }
            version.incrementAndGet();
        }
    }

    public void saveAsync(Path file) {
        try {
            worker.execute(() -> {
                try {
                    save(file);
                } catch (Throwable t) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Could not save far light file {}: {}", file, t.toString());
                }
            });
        } catch (RuntimeException shutDown) {
        }
    }

    public void loadAsync(Path file) {
        try {
            worker.execute(() -> {
                try {
                    load(file);
                    ColorLightClient.LOGGER.info("[ColorLight] Loaded far light from {} ({} columns)", file, columns.size());
                } catch (Throwable t) {
                    ColorLightClient.LOGGER.warn("[ColorLight] Could not read far light file {}: {}", file, t.toString());
                }
            });
        } catch (RuntimeException shutDown) {
        }
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
            scanAndStore(level, cx, cz);
            if (System.nanoTime() > deadline)
                return sweepIdx < SWEEP_DX.length;
        }
        sweepActive = false;
        everCompletedSweep = true;
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
            sourceCount.addAndGet(fresh.length / 4 - (old == null ? 0 : old.length / 4));
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
                    result = rgb;
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
        filler.shutdownNow();
        if (dataCache != null) {
            try {
                dataCache.close();
            } catch (Throwable ignored) {
            }
            dataCache = null;
        }
    }
}
