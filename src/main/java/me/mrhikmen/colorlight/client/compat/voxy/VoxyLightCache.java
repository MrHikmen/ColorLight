package me.mrhikmen.colorlight.client.compat.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private static final int FILE_MAGIC = 0x434C5658; // 'CLVX'
    private static final int FILE_FORMAT = 4;         // 4: only exposed lights are listed (older far-light files are ignored)

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

    /** Section column key (x,z of a level-0 section) -> flat {x, y, z, rgb} per source. Present (even empty) = scanned. */
    private final ConcurrentHashMap<Long, int[]> columns = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private final AtomicBoolean unsaved = new AtomicBoolean();

    private final ConcurrentHashMap<Long, Long> dirtyCols = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<long[]> incomingEngineDirty = new ConcurrentLinkedQueue<>();

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ColorLight Voxy Light Scanner");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final ExecutorService filler = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ColorLight Voxy Light Table");
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

    // sweep: scanning every column around the player once (nearest first)
    private volatile boolean sweepActive;
    private volatile int sweepIdx;
    private volatile int sweepCenterX = Integer.MIN_VALUE, sweepCenterZ;

    // GPU table
    private static final int FILL_IDLE = 0, FILL_REQUESTED = 1, FILL_READY = 2;
    private final AtomicInteger fillState = new AtomicInteger(FILL_IDLE);
    private final long[] fillStamp = new long[4];
    private IntBuffer cellBuf;
    private IntBuffer srcBuf;

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
        columns.clear();
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
        WorldEngine engine = instance.getNullable(id);
        if (engine == null || !engine.isLive())
            return;
        var player = Minecraft.getInstance().player;
        if (player == null)
            return;
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
                columns.putIfAbsent(key, a);
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

    private boolean runSweep(WorldEngine engine, long deadline) {
        int cx0 = sweepCenterX, cz0 = sweepCenterZ;
        while (sweepActive && sweepIdx < SWEEP_DX.length) {
            int i = sweepIdx;
            int cx = cx0 + SWEEP_DX[i];
            int cz = cz0 + SWEEP_DZ[i];
            sweepIdx = i + 1;
            if (!columns.containsKey(colKey(cx, cz))) {
                scanAndStore(engine, cx, cz);
                if (System.nanoTime() > deadline)
                    return sweepIdx < SWEEP_DX.length;
            }
        }
        sweepActive = false;
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
        if (old == null || !Arrays.equals(old, fresh)) {
            version.incrementAndGet();
            unsaved.set(true);
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
                if (brightness >= MIN_BRIGHTNESS)
                    result = rgb;
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
        filler.shutdownNow();
    }
}
