package me.mrhikmen.colorlight.core.light.engine;

import me.mrhikmen.colorlight.core.light.color.ColorLightUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static me.mrhikmen.colorlight.core.light.engine.PropagationTables.*;

/**
 * Coloured light field: a flood-fill engine for static sources (blocks) plus a separate layer for
 * moving sources (entities).
 *
 * <h3>Design notes</h3>
 * <ul>
 *     <li><b>No allocation in hot loops.</b> Everything runs on packed {@code long} keys / raw ints
 *     ({@link PosKey}), primitive queues and primitive per-pass memo maps ({@link PassMap}).</li>
 *     <li><b>One block-state lookup per cell per pass.</b> Opacity is memoized for the duration of a
 *     pass; a smooth flood used to ask the level for the same cell up to 26 times.</li>
 *     <li><b>Thread-safe mutation.</b> Every mutation of the static field takes {@link #lock}; before,
 *     the chunk-apply thread and the game thread could flood/darken concurrently. Reads (the mesher)
 *     stay lock-free.</li>
 *     <li><b>Exact dirty tracking.</b> Each changed cell records the render section(s) whose mesh depends
 *     on it; {@link #drainDirtySections()} hands them to the flusher, which replaces the old
 *     "mark a (2R+2)^3 block cube dirty after every change".</li>
 *     <li><b>Dynamic lights live in their own layer</b> ({@link DynamicLightLayer}); sampling returns
 *     {@code max(static, dynamic)}. Moving a light never runs a darkening pass through static light.</li>
 * </ul>
 * The stored int per cell is {@code flags(8) | b(8) | g(8) | r(8)}; flags mark a cell as a source
 * (and which propagation model it uses) so {@link #hasSource} is a lock-free single read.
 */
public class ColorLightEngine {

    protected static final int VANILLA_MAX_OPACITY = 15;

    static final int RGB_MASK = LightStorage.RGB_MASK;
    static final int FLAG_SOURCE = 1 << 24;
    static final int FLAG_SMOOTH = 1 << 25;
    static final int FLAG_MASK = 0xFF000000;

    /** 1 / 0.125 - the finest sub-unit step SMOOTH tracks internally. */
    private static final int FIXED_POINT_SCALE = 8;

    /** How many sources are flooded per lock acquisition when adding a batch (keeps game-thread stalls short). */
    private static final int BATCH_GROUP_SIZE = 4;

    private static final long[] NO_KEYS = new long[0];
    private static final long TIME_FACTOR_TTL_NANOS = 50_000_000L;

    private final int maxRangeBlocks;
    protected final float decayPerOpacityUnit;
    protected final LevelAccessor level;
    private final ColorLightPropagationMode propagationMode;

    private final int[] gridDecay;
    private final int[][] smoothDecay;

    private final LightStorage data = new LightStorage();
    private final DynamicLightLayer dynamic;

    /** key -> the source's own (pre-spread) colour. Guarded by {@link #lock}. */
    private final LongIntMap sourceColors = new LongIntMap(256);
    /** Sections changed by the mutation currently running. Guarded by {@link #lock}; published by {@link #unlock()}. */
    private final LongIntMap dirtySections = new LongIntMap(256);
    /**
     * Sections waiting to be handed to the renderer. Guarded by its own tiny monitor, so the game thread
     * can read and add to it without ever waiting for a flood that holds {@link #lock}. Floods publish into it
     * at the end of every locked operation (see {@link #unlock()}), so light changes reach the renderer
     * promptly even while the apply thread keeps the lock busy back-to-back.
     */
    private final LongIntMap externalDirty = new LongIntMap(256);

    private final ReentrantLock lock = new ReentrantLock();

    // ---- scratch state, only touched while holding the lock ----
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final PassMap opacityMemo = new PassMap(1 << 12);
    private final PassMap eighthsMemo = new PassMap(1 << 12);
    private final PassMap seedSeen = new PassMap(1 << 10);
    private final LongQueue gridQueue = new LongQueue(1 << 10);
    private final LongQueue smoothQueue = new LongQueue(1 << 10);
    private final LongQueue darkenKeys = new LongQueue(1 << 10);
    private final IntQueue darkenColors = new IntQueue(1 << 10);

    private volatile int sourceVersion;
    private volatile SourceSnapshot snapshot;

    private volatile long timeFactorStamp;
    private volatile float timeFactor;

    /** Releases {@link #lock}, first publishing the sections this operation touched to {@link #externalDirty}. */
    private void unlock() {
        try {
            if (!dirtySections.isEmpty()) {
                long[] keys = dirtySections.keysToArray();
                dirtySections.clear();
                synchronized (externalDirty) {
                    for (long key : keys) {
                        externalDirty.put(key, 1);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public ColorLightEngine(LevelAccessor level, int maxRangeBlocks) {
        this(level, maxRangeBlocks, ColorLightPropagationMode.GRID);
    }

    public ColorLightEngine(LevelAccessor level, int maxRangeBlocks, ColorLightPropagationMode propagationMode) {
        this.level = level;
        this.maxRangeBlocks = Math.max(1, maxRangeBlocks); // защита от 0/отрицательных значений из конфига
        this.decayPerOpacityUnit = ColorLightUtil.MAX / (float) this.maxRangeBlocks;
        this.propagationMode = (propagationMode != null) ? propagationMode : ColorLightPropagationMode.GRID;
        this.gridDecay = buildGridDecay(decayPerOpacityUnit);
        this.smoothDecay = buildSmoothDecay(decayPerOpacityUnit, FIXED_POINT_SCALE);
        this.dynamic = new DynamicLightLayer(this::markDirty);
    }

    // =====================================================================================
    // Simple accessors
    // =====================================================================================

    public int getMaxRangeBlocks() {
        return maxRangeBlocks;
    }

    /** The engine-wide default model (what plain block sources use). Individual sources may override it. */
    public ColorLightPropagationMode getPropagationMode() {
        return propagationMode;
    }

    public float getDecayPerOpacityUnit() {
        return decayPerOpacityUnit;
    }

    /** Colour of a cell as the renderer sees it: static and dynamic light merged. */
    public int getColor(BlockPos pos) {
        return getColor(pos.getX(), pos.getY(), pos.getZ());
    }

    public int getColor(int x, int y, int z) {
        int color = data.get(x, y, z) & RGB_MASK;
        if (dynamic.isActive()) {
            int dyn = dynamic.storage().get(x, y, z);
            if (dyn != 0)
                color = ColorLightUtil.max(color, dyn);
        }
        return color;
    }

    /** Static (block) light only - what block edits can affect. */
    public int getStaticColor(BlockPos pos) {
        return data.get(pos.getX(), pos.getY(), pos.getZ()) & RGB_MASK;
    }

    /** Lock-free: reads the source flag stored with the cell. */
    public boolean hasSource(BlockPos pos) {
        return (data.get(pos.getX(), pos.getY(), pos.getZ()) & FLAG_SOURCE) != 0;
    }

    public boolean isOpaque(BlockPos pos) {
        return getOpacity(pos) >= VANILLA_MAX_OPACITY;
    }

    protected int getOpacity(BlockPos pos) {
        return Math.max(0, Math.min(VANILLA_MAX_OPACITY, level.getBlockState(pos).getLightDampening()));
    }

    /**
     * True if neither this block's section nor (when the block touches a section border) the
     * adjacent ones contain any light at all. A mesh block within a lit-free area can skip all
     * light sampling: the answer is "white" for every vertex.
     */
    public boolean isLightFreeAround(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        int lx = x & 15, ly = y & 15, lz = z & 15;
        int x0 = lx == 0 ? sx - 1 : sx, x1 = lx == 15 ? sx + 1 : sx;
        int y0 = ly == 0 ? sy - 1 : sy, y1 = ly == 15 ? sy + 1 : sy;
        int z0 = lz == 0 ? sz - 1 : sz, z1 = lz == 15 ? sz + 1 : sz;

        boolean dyn = dynamic.isActive();
        for (int a = x0; a <= x1; a++) {
            for (int b = y0; b <= y1; b++) {
                for (int c = z0; c <= z1; c++) {
                    if (!data.isSectionEmpty(a, b, c))
                        return false;
                    if (dyn && !dynamic.storage().isSectionEmpty(a, b, c))
                        return false;
                }
            }
        }
        return true;
    }

    // =====================================================================================
    // Source snapshot
    // =====================================================================================

    private static final SourceSnapshot EMPTY_SNAPSHOT = new SourceSnapshot(new long[0], new int[0], -1);

    /**
     * Cached until the set of static sources changes. Never blocks the caller (usually the render or
     * game thread): if a flood is running while the snapshot is out of date, the previous one is
     * returned and the fresh one is built by whichever call finds the lock free.
     */
    public SourceSnapshot getSourceSnapshot() {
        SourceSnapshot current = snapshot;
        int version = sourceVersion;
        if (current != null && current.version() == version)
            return current;

        if (!lock.tryLock())
            return current != null ? current : EMPTY_SNAPSHOT;
        try {
            version = sourceVersion;
            current = snapshot;
            if (current != null && current.version() == version)
                return current;

            long[] keys = sourceColors.keysToArray();
            int[] colors = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                colors[i] = sourceColors.get(keys[i], 0);
            }
            current = new SourceSnapshot(keys, colors, version);
            snapshot = current;
            return current;
        } finally {
            lock.unlock(); // read-only: nothing to publish
        }
    }

    public List<BlockPos> getSourcePositions() {
        return getSourceSnapshot().positions();
    }

    // =====================================================================================
    // Static sources
    // =====================================================================================

    /** Adds a source using the engine's default model (plain blocks). */
    public void addSource(BlockPos pos, int r, int g, int b, int strength) {
        addSource(pos, r, g, b, strength, propagationMode);
    }

    /**
     * Adds a source that spreads using {@code mode} specifically, independent of the engine's default.
     * Re-adding an identical source is a no-op; re-adding it with a different colour/model first
     * removes the old one so stale light around it is cleaned up.
     */
    public void addSource(BlockPos pos, int r, int g, int b, int strength, ColorLightPropagationMode mode) {
        ColorLightPropagationMode resolved = (mode != null) ? mode : propagationMode;

        float scale = ColorLightUtil.clamp01(strength / 15f);
        int packed = ColorLightUtil.pack(Math.round(r * scale), Math.round(g * scale), Math.round(b * scale));

        lock.lock();
        try {
            addSourceLocked(pos.getX(), pos.getY(), pos.getZ(), packed, resolved);
        } finally {
            unlock();
        }
    }

    /**
     * Adds many sources of the engine's default model at once, e.g. everything found in a freshly
     * loaded chunk. Sources are flooded together in one pass per group instead of one flood each.
     *
     * @return true if the light field changed
     */
    public boolean addSources(SourceBatch batch) {
        if (batch == null || batch.isEmpty())
            return false;

        boolean changed = false;
        int total = batch.size();
        for (int from = 0; from < total; from += BATCH_GROUP_SIZE) {
            int to = Math.min(total, from + BATCH_GROUP_SIZE);
            lock.lock();
            try {
                gridQueue.clear();
                smoothQueue.clear();
                for (int i = from; i < to; i++) {
                    long key = batch.key(i);
                    changed |= placeSourceLocked(PosKey.x(key), PosKey.y(key), PosKey.z(key), batch.color(i), propagationMode);
                }
                flushQueuesLocked();
            } finally {
                unlock();
            }
        }
        return changed;
    }

    private void addSourceLocked(int x, int y, int z, int packed, ColorLightPropagationMode mode) {
        gridQueue.clear();
        smoothQueue.clear();
        placeSourceLocked(x, y, z, packed, mode);
        flushQueuesLocked();
    }

    /** Writes the source cell and queues it as a seed; the caller floods afterwards. @return whether anything changed */
    private boolean placeSourceLocked(int x, int y, int z, int packed, ColorLightPropagationMode mode) {
        long key = PosKey.pack(x, y, z);
        int flags = FLAG_SOURCE | (mode == ColorLightPropagationMode.SMOOTH ? FLAG_SMOOTH : 0);

        int existing = data.get(x, y, z);
        if ((existing & FLAG_SOURCE) != 0) {
            if ((existing & (FLAG_MASK)) == flags && sourceColors.get(key, -1) == packed)
                return false; // identical source already there

            // different colour/strength/model: clean up what the old one lit before adding the new one
            flushQueuesLocked(); // don't lose seeds already queued for this batch group
            removeSourceLocked(x, y, z);
            existing = data.get(x, y, z);
        }

        sourceColors.put(key, packed);
        sourceVersion++;

        int merged = ColorLightUtil.max(existing & RGB_MASK, packed);
        data.put(x, y, z, flags | merged);
        markDirty(x, y, z);

        (mode == ColorLightPropagationMode.SMOOTH ? smoothQueue : gridQueue).add(key);
        return true;
    }

    public void removeSource(BlockPos pos) {
        lock.lock();
        try {
            removeSourceLocked(pos.getX(), pos.getY(), pos.getZ());
        } finally {
            unlock();
        }
    }

    private void removeSourceLocked(int x, int y, int z) {
        long key = PosKey.pack(x, y, z);
        int cell = data.get(x, y, z);
        if ((cell & FLAG_SOURCE) == 0)
            return;

        sourceColors.remove(key);
        sourceVersion++;

        int old = cell & RGB_MASK;
        data.put(x, y, z, 0);
        if (old != 0)
            markDirty(x, y, z);

        darkenAndCollectSeeds(key, old);
        flushQueuesLocked();
    }

    /**
     * Called after a block changed. Invalidates any dynamic footprint the block touches and, if
     * the change can affect static light, darkens and re-floods the affected region.
     */
    public void onBlockChanged(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();

        lock.lock();
        try {
            dynamic.markStaleAround(x, y, z);

            int cell = data.get(x, y, z);
            if ((cell & FLAG_SOURCE) != 0)
                return;
            if (!hasStaticLightNear(x, y, z))
                return;

            long key = PosKey.pack(x, y, z);
            int old = cell & RGB_MASK;
            data.put(x, y, z, 0);
            if (old != 0)
                markDirty(x, y, z);

            darkenAndCollectSeeds(key, old);

            seedOnce(propagationMode, key);
            for (int n = 0; n < SMOOTH_COUNT; n++) {
                int nx = x + SMOOTH_DX[n], ny = y + SMOOTH_DY[n], nz = z + SMOOTH_DZ[n];
                int nCell = data.get(nx, ny, nz);
                if ((nCell & RGB_MASK) == 0 && (nCell & FLAG_SOURCE) == 0)
                    continue; // nothing there that could spread into the changed cell
                seedOnce(modeOf(nCell), PosKey.pack(nx, ny, nz));
            }

            flushQueuesLocked();
        } finally {
            unlock();
        }
    }

    /**
     * Lock-free: is there any static light in the 3x3x3 cells around this block? If not, a block change there
     * cannot affect static light and needn't be queued at all.
     */
    public boolean hasStaticLightNear(BlockPos pos) {
        return hasStaticLightNear(pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean hasStaticLightNear(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((data.get(x + dx, y + dy, z + dz) & RGB_MASK) != 0)
                        return true;
                }
            }
        }
        return false;
    }

    /** Drops all light. Every previously lit section is marked dirty first so the renderer refreshes it. */
    public void clearAll() {
        lock.lock();
        try {
            data.forEachLitSection(this::markSectionAndNeighborsDirty);
            dynamic.storage().forEachLitSection(this::markSectionAndNeighborsDirty);
            data.clear();
            dynamic.clear();
            sourceColors.clear();
            sourceVersion++;
        } finally {
            unlock();
        }
    }

    /** See {@link #removeChunks(long[])}. */
    public void removeChunk(int chunkX, int chunkZ) {
        removeChunks(new long[]{chunkKey(chunkX, chunkZ)});
    }

    /** Packs chunk coordinates for {@link #removeChunks(long[])}. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return PosKey.pack(chunkX, 0, chunkZ);
    }

    /**
     * Forgets everything about the given chunk columns (sources and light values) after they unloaded.
     * All chunks are handled in a <b>single</b> pass over the source table, so a burst of unloads costs
     * one scan of the sources instead of one scan per chunk. Light that spilled into still-loaded
     * neighbours is left alone: it sits at the edge of the render distance and disappears/refreshes as
     * soon as those chunks change or unload too.
     *
     * @param chunkKeys keys from {@link #chunkKey(int, int)}
     */
    public void removeChunks(long[] chunkKeys) {
        if (chunkKeys == null || chunkKeys.length == 0)
            return;

        LongIntMap wanted = new LongIntMap(chunkKeys.length * 2);
        for (long key : chunkKeys) {
            wanted.put(key, 1);
        }

        lock.lock();
        try {
            long[][] holder = {new long[16]};
            int[] count = {0};
            // collect first (removing while iterating would shift entries under the iterator)
            sourceColors.forEachKey(key -> {
                if (!wanted.containsKey(chunkKey(PosKey.x(key) >> 4, PosKey.z(key) >> 4)))
                    return;
                if (count[0] == holder[0].length)
                    holder[0] = java.util.Arrays.copyOf(holder[0], count[0] << 1);
                holder[0][count[0]++] = key;
            });

            for (int i = 0; i < count[0]; i++) {
                sourceColors.remove(holder[0][i]);
            }
            if (count[0] > 0)
                sourceVersion++;

            for (long key : chunkKeys) {
                data.removeColumn(PosKey.x(key), PosKey.z(key));
            }
        } finally {
            unlock();
        }
    }

    // =====================================================================================
    // Flood fill
    // =====================================================================================

    private ColorLightPropagationMode modeOf(int cell) {
        if ((cell & FLAG_SOURCE) != 0)
            return (cell & FLAG_SMOOTH) != 0 ? ColorLightPropagationMode.SMOOTH : ColorLightPropagationMode.GRID;
        return propagationMode;
    }

    private void flushQueuesLocked() {
        if (!gridQueue.isEmpty())
            propagateGrid(gridQueue);
        if (!smoothQueue.isEmpty())
            propagateSmooth(smoothQueue);
    }

    private int opacityAt(int x, int y, int z) {
        long key = PosKey.pack(x, y, z);
        long cached = opacityMemo.get(key, -1L);
        if (cached >= 0)
            return (int) cached;

        scratchPos.set(x, y, z);
        int opacity = level.getBlockState(scratchPos).getLightDampening();
        opacity = Math.max(0, Math.min(VANILLA_MAX_OPACITY, opacity));
        opacityMemo.put(key, opacity);
        return opacity;
    }

    /** Strictly axis-aligned, whole-unit decay per hop. */
    private void propagateGrid(LongQueue queue) {
        opacityMemo.clear();

        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);

            int cur = data.get(x, y, z);
            int r = cur & 0xFF, g = (cur >> 8) & 0xFF, b = (cur >> 16) & 0xFF;
            if ((r | g | b) == 0)
                continue;

            for (int n = 0; n < 6; n++) {
                int nx = x + GRID_DX[n], ny = y + GRID_DY[n], nz = z + GRID_DZ[n];

                int opacity = opacityAt(nx, ny, nz);
                if (opacity >= VANILLA_MAX_OPACITY)
                    continue;

                int decay = gridDecay[opacity];
                int nr = r - decay; if (nr < 0) nr = 0;
                int ng = g - decay; if (ng < 0) ng = 0;
                int nb = b - decay; if (nb < 0) nb = 0;
                if ((nr | ng | nb) == 0)
                    continue;

                int nCell = data.get(nx, ny, nz);
                int cr = nCell & 0xFF, cg = (nCell >> 8) & 0xFF, cb = (nCell >> 16) & 0xFF;

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;
                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    data.put(nx, ny, nz, (nCell & FLAG_MASK) | fr | (fg << 8) | (fb << 16));
                    markDirty(nx, ny, nz);
                    queue.add(PosKey.pack(nx, ny, nz));
                }
            }
        }
        opacityMemo.trim();
    }

    /**
     * Hops through all 26 neighbours with decay scaled by real Euclidean distance. The running
     * value is tracked in 1/8-unit fixed point for the duration of the pass and only rounded to whole
     * units when written out, exactly as before.
     */
    private void propagateSmooth(LongQueue queue) {
        opacityMemo.clear();
        eighthsMemo.clear();

        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);

            long curEighths = eighthsMemo.get(key, -1L);
            if (curEighths < 0) {
                curEighths = toEighths(data.get(x, y, z));
                eighthsMemo.put(key, curEighths);
            }

            int r = eighthsR(curEighths), g = eighthsG(curEighths), b = eighthsB(curEighths);
            if ((r | g | b) == 0)
                continue;

            for (int n = 0; n < SMOOTH_COUNT; n++) {
                int nx = x + SMOOTH_DX[n], ny = y + SMOOTH_DY[n], nz = z + SMOOTH_DZ[n];

                int opacity = opacityAt(nx, ny, nz);
                if (opacity >= VANILLA_MAX_OPACITY)
                    continue;

                int decay = smoothDecay[n][opacity];
                int nr = r - decay; if (nr < 0) nr = 0;
                int ng = g - decay; if (ng < 0) ng = 0;
                int nb = b - decay; if (nb < 0) nb = 0;
                if ((nr | ng | nb) == 0)
                    continue;

                long nKey = PosKey.pack(nx, ny, nz);
                int nCell = data.get(nx, ny, nz);

                long nEighths = eighthsMemo.get(nKey, -1L);
                if (nEighths < 0)
                    nEighths = toEighths(nCell);

                int cr = eighthsR(nEighths), cg = eighthsG(nEighths), cb = eighthsB(nEighths);

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;
                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    eighthsMemo.put(nKey, packEighths(fr, fg, fb));

                    int rounded = ColorLightUtil.pack(fromEighths(fr), fromEighths(fg), fromEighths(fb));
                    if (rounded != (nCell & RGB_MASK)) {
                        data.put(nx, ny, nz, (nCell & FLAG_MASK) | rounded);
                        markDirty(nx, ny, nz);
                    }
                    queue.add(nKey);
                }
            }
        }
        opacityMemo.trim();
        eighthsMemo.trim();
    }

    private static long toEighths(int packedByteColor) {
        return packEighths(
                (packedByteColor & 0xFF) * FIXED_POINT_SCALE,
                ((packedByteColor >> 8) & 0xFF) * FIXED_POINT_SCALE,
                ((packedByteColor >> 16) & 0xFF) * FIXED_POINT_SCALE);
    }

    private static int fromEighths(int eighthsValue) {
        return ColorLightUtil.clamp(Math.round(eighthsValue / (float) FIXED_POINT_SCALE));
    }

    /**
     * Invalidates the region downstream of a removed/changed colour and queues the cells that
     * must radiate again into the grid / smooth seed queues.
     * <p>
     * The search always walks the full 26-neighbour graph (a superset of the 6-neighbour one).
     * An encountered source re-lights with its own model; an ordinary surviving cell falls back to
     * the engine default. Two refinements over the old version, both covered by tests:
     * <ul>
     *     <li>a cell is only re-seeded if it holds an independent value in a channel the removed light
     *     actually carried (previously any non-empty neighbour was, which for coloured light meant
     *     re-flooding half the surroundings), and each cell is seeded once;</li>
     *     <li>a source cell that had been raised by the removed light is reset to its own colour
     *     instead of keeping the stale glow forever.</li>
     * </ul>
     */
    private void darkenAndCollectSeeds(long startKey, int oldColorAtStart) {
        gridQueue.clear();
        smoothQueue.clear();
        darkenKeys.clear();
        darkenColors.clear();
        seedSeen.clear();

        if (oldColorAtStart == 0)
            return;

        darkenKeys.add(startKey);
        darkenColors.add(oldColorAtStart);

        while (!darkenKeys.isEmpty()) {
            long curKey = darkenKeys.poll();
            int curColor = darkenColors.poll();
            int r = curColor & 0xFF, g = (curColor >> 8) & 0xFF, b = (curColor >> 16) & 0xFF;

            int x = PosKey.x(curKey), y = PosKey.y(curKey), z = PosKey.z(curKey);

            for (int n = 0; n < SMOOTH_COUNT; n++) {
                int nx = x + SMOOTH_DX[n], ny = y + SMOOTH_DY[n], nz = z + SMOOTH_DZ[n];
                int nCell = data.get(nx, ny, nz);

                int nr = nCell & 0xFF, ng = (nCell >> 8) & 0xFF, nb = (nCell >> 16) & 0xFF;

                if ((nCell & FLAG_SOURCE) != 0) {
                    long nKey = PosKey.pack(nx, ny, nz);
                    int own = sourceColors.get(nKey, 0);
                    int or = own & 0xFF, og = (own >> 8) & 0xFF, ob = (own >> 16) & 0xFF;

                    // channels above the source's own value but below what was removed were fed by the removed light
                    int fr = (nr > or && nr < r) ? or : nr;
                    int fg = (ng > og && ng < g) ? og : ng;
                    int fb = (nb > ob && nb < b) ? ob : nb;

                    if (fr != nr || fg != ng || fb != nb) {
                        data.put(nx, ny, nz, (nCell & FLAG_MASK) | fr | (fg << 8) | (fb << 16));
                        markDirty(nx, ny, nz);
                        darkenKeys.add(nKey);
                        darkenColors.add(nr | (ng << 8) | (nb << 16));
                    }
                    seedOnce(modeOf(nCell), nKey);
                    continue;
                }

                if ((nr | ng | nb) == 0)
                    continue;

                int fr = nr, fg = ng, fb = nb;
                boolean darkened = false;
                if (nr != 0 && nr < r) { fr = 0; darkened = true; }
                if (ng != 0 && ng < g) { fg = 0; darkened = true; }
                if (nb != 0 && nb < b) { fb = 0; darkened = true; }

                long nKey = PosKey.pack(nx, ny, nz);

                if (darkened) {
                    data.put(nx, ny, nz, fr | (fg << 8) | (fb << 16));
                    markDirty(nx, ny, nz);
                    darkenKeys.add(nKey);
                    darkenColors.add(nr | (ng << 8) | (nb << 16));
                }

                // independent light in a channel the removed light carried -> must radiate again
                if ((r > 0 && nr >= r) || (g > 0 && ng >= g) || (b > 0 && nb >= b)) {
                    seedOnce(propagationMode, nKey);
                }
            }
        }
    }

    private void seedOnce(ColorLightPropagationMode mode, long key) {
        if (seedSeen.contains(key))
            return;
        seedSeen.put(key, 1L);
        (mode == ColorLightPropagationMode.SMOOTH ? smoothQueue : gridQueue).add(key);
    }

    // =====================================================================================
    // Dynamic (entity) lights
    // =====================================================================================

    /**
     * Creates a helper that computes dynamic-light footprints off the game thread. Give each
     * worker thread its own instance.
     */
    public DynamicLightWorker newDynamicWorker() {
        return new DynamicLightWorker(new DynamicFlood(level, decayPerOpacityUnit));
    }

    /** Thread-confined handle used to (re)compute and apply one dynamic light at a time. */
    public final class DynamicLightWorker {
        private final DynamicFlood flood;

        private DynamicLightWorker(DynamicFlood flood) {
            this.flood = flood;
        }

        /**
         * Floods the light at its exact sub-block position (no engine lock needed) and then applies
         * the result to the dynamic layer under the lock, changing only the cells that differ.
         */
        public void update(int id, double x, double y, double z, int r, int g, int b, int strength) {
            DynamicFootprint footprint = flood.compute(x, y, z, r, g, b, strength);
            lock.lock();
            try {
                dynamic.replace(id, footprint);
            } finally {
                unlock();
            }
        }

        public void remove(int id) {
            lock.lock();
            try {
                dynamic.remove(id);
            } finally {
                unlock();
            }
        }
    }

    /** True if a block changed inside this dynamic light's footprint since it was computed. */
    public boolean isDynamicStale(int id) {
        return dynamic.isStale(id);
    }

    /** Flags dynamic lights near a changed block for recomputation without touching static light. Lock-free. */
    public void invalidateDynamicAround(BlockPos pos) {
        dynamic.markStaleAround(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean hasDynamic(int id) {
        return dynamic.has(id);
    }

    // =====================================================================================
    // Dirty section tracking
    // =====================================================================================

    /** The cell's colour changed: every mesh block within 1 cell of it reads it, so mark those sections. */
    private void markDirty(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        int lx = x & 15, ly = y & 15, lz = z & 15;
        int x0 = lx == 0 ? sx - 1 : sx, x1 = lx == 15 ? sx + 1 : sx;
        int y0 = ly == 0 ? sy - 1 : sy, y1 = ly == 15 ? sy + 1 : sy;
        int z0 = lz == 0 ? sz - 1 : sz, z1 = lz == 15 ? sz + 1 : sz;
        for (int a = x0; a <= x1; a++) {
            for (int b = y0; b <= y1; b++) {
                for (int c = z0; c <= z1; c++) {
                    dirtySections.put(PosKey.pack(a, b, c), 1);
                }
            }
        }
    }

    private void markSectionAndNeighborsDirty(long sectionKey) {
        int sx = PosKey.x(sectionKey), sy = PosKey.y(sectionKey), sz = PosKey.z(sectionKey);
        for (int a = sx - 1; a <= sx + 1; a++) {
            for (int b = sy - 1; b <= sy + 1; b++) {
                for (int c = sz - 1; c <= sz + 1; c++) {
                    dirtySections.put(PosKey.pack(a, b, c), 1);
                }
            }
        }
    }

    /** Marks every section touched by the block box (padded by one block) as needing a rebuild. Never waits for a flood. */
    public void markRegionDirty(int x0, int y0, int z0, int x1, int y1, int z1) {
        int sx0 = (x0 - 1) >> 4, sx1 = (x1 + 1) >> 4;
        int sy0 = (y0 - 1) >> 4, sy1 = (y1 + 1) >> 4;
        int sz0 = (z0 - 1) >> 4, sz1 = (z1 + 1) >> 4;
        synchronized (externalDirty) {
            for (int a = sx0; a <= sx1; a++) {
                for (int b = sy0; b <= sy1; b++) {
                    for (int c = sz0; c <= sz1; c++) {
                        externalDirty.put(PosKey.pack(a, b, c), 1);
                    }
                }
            }
        }
    }

    /** After replacing an engine (config change): rebuild everything the old engine had lit, since it just went away. */
    public void inheritDirtyFrom(ColorLightEngine old) {
        if (old == null || old == this)
            return;
        List<Long> keys = new java.util.ArrayList<>();
        old.data.forEachLitSection(keys::add);
        old.dynamic.storage().forEachLitSection(keys::add);
        synchronized (externalDirty) {
            for (long key : keys) {
                int sx = PosKey.x(key), sy = PosKey.y(key), sz = PosKey.z(key);
                for (int a = sx - 1; a <= sx + 1; a++) {
                    for (int b = sy - 1; b <= sy + 1; b++) {
                        for (int c = sz - 1; c <= sz + 1; c++) {
                            externalDirty.put(PosKey.pack(a, b, c), 1);
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns and clears the packed section coordinates ({@link PosKey}) that need a mesh rebuild.
     * Only touches {@link #externalDirty}'s own monitor, so it never waits for a flood.
     */
    public long[] drainDirtySections() {
        synchronized (externalDirty) {
            if (externalDirty.isEmpty())
                return NO_KEYS;
            long[] out = externalDirty.keysToArray();
            externalDirty.clear();
            return out;
        }
    }

    // =====================================================================================
    // Daylight
    // =====================================================================================

    public float getDaylightFactor(BlockPos pos) {
        if (!(level instanceof Level realLevel))
            return 0f;

        float timeOfDayFactor = cachedTimeOfDayFactor(realLevel);
        if (timeOfDayFactor <= 0f)
            return 0f; // night: skip reading sky light altogether

        float skyExposure = realLevel.getBrightness(LightLayer.SKY, pos) / 15f;
        return ColorLightUtil.clamp01(skyExposure * timeOfDayFactor);
    }

    private float cachedTimeOfDayFactor(Level realLevel) {
        long now = System.nanoTime();
        if (now - timeFactorStamp > TIME_FACTOR_TTL_NANOS) {
            timeFactor = computeTimeOfDayFactor(realLevel);
            timeFactorStamp = now;
        }
        return timeFactor;
    }

    public static float computeTimeOfDayFactor(Level level) {
        long dayTime = level.getDefaultClockTime() % 24000L;
        if (dayTime < 0)
            dayTime += 24000L;

        if (dayTime < 12000) return 1f;
        if (dayTime < 12100) return 0.9f;
        if (dayTime < 12200) return 0.8f;
        if (dayTime < 12300) return 0.7f;
        if (dayTime < 12400) return 0.6f;
        if (dayTime < 12500) return 0.5f;
        if (dayTime < 12600) return 0.4f;
        if (dayTime < 12700) return 0.3f;
        if (dayTime < 12800) return 0.2f;
        if (dayTime < 12900) return 0.1f;
        if (dayTime < 23000) return 0f;
        if (dayTime < 23100) return 0.1f;
        if (dayTime < 23200) return 0.2f;
        if (dayTime < 23300) return 0.3f;
        if (dayTime < 23400) return 0.4f;
        if (dayTime < 23500) return 0.5f;
        if (dayTime < 23600) return 0.6f;
        if (dayTime < 23700) return 0.7f;
        if (dayTime < 23800) return 0.8f;
        if (dayTime < 23900) return 0.9f;
        return 1f;
    }

    public String debugDaylight(BlockPos pos) {
        if (!(level instanceof Level realLevel))
            return "[ColorLight] Level is not a real Level (" + level.getClass() + ")";

        long dayTimeRaw = realLevel.getDefaultClockTime();
        long dayTime = dayTimeRaw % 24000L;
        if (dayTime < 0) dayTime += 24000L;

        int rawSky = realLevel.getBrightness(LightLayer.SKY, pos);
        float skyExposure = rawSky / 15f;
        float timeFactor = computeTimeOfDayFactor(realLevel);
        float finalFactor = getDaylightFactor(pos);

        return "dayTimeRaw=" + dayTimeRaw + " dayTime%24000=" + dayTime + " rawSky=" + rawSky + " skyExposure=" + skyExposure + " timeFactor=" + timeFactor + " finalDaylightFactor=" + finalFactor + " defaultPropagationMode=" + propagationMode;
    }

    public int sampleFlatColor(BlockPos pos, net.minecraft.core.Direction face) {
        BlockPos facePos = (face != null) ? pos.relative(face) : pos;
        return ColorLightUtil.max(getColor(pos), getColor(facePos));
    }
}
