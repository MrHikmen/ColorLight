package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.resourcepack.data.JsonPropagationMethod;
import me.mrhikmen.colorlight.client.core.light.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.client.core.light.cell.LightCell;
import me.mrhikmen.colorlight.client.core.light.cell.LightPalette;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator;
import me.mrhikmen.colorlight.client.core.light.scan.ColorLightChunkScanner;
import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.light.util.IntQueue;
import me.mrhikmen.colorlight.client.core.light.util.LongIntMap;
import me.mrhikmen.colorlight.client.core.light.util.LongQueue;
import me.mrhikmen.colorlight.client.core.light.util.PassMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.*;

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
 * The stored value per cell is a {@link LightCell}: up to two lights (a brightness and the id of a colour in the
 * {@link #palette()}) plus flags that mark the cell as a source (and which propagation model it uses), so
 * {@link #hasSource} is a lock-free single read. A flood spreads one number, the brightness of one colour; the colour
 * itself never changes on the way.
 */
public class ColorLightEngine {

    protected static final int VANILLA_MAX_OPACITY = MAX_OPACITY;

    /** The engine's flags live in the top bits of a {@link LightCell}. */
    private static final long FLAG_MASK = LightCell.FLAGS;
    /** The flags the engine itself sets (the rest of the flag bits belong to {@link LightCell}). */
    private static final long ENGINE_FLAGS = (1L << 32) | (1L << 34);

    static final long FLAG_SOURCE = 1L << 32;
    /**
     * Set on a source cell that spreads with a propagation method other than the engine's default one -
     * which method exactly is looked up in {@link #customMethodByKey}, since an arbitrary number of
     * methods (from resource pack JSON or other mods) can't each get their own bit here. A source without this
     * flag uses the default method.
     */
    static final long FLAG_CUSTOM = 1L << 34;

    /** Most seeds (of one colour, in one chunk column) flooded in one go, see {@link #buildTasks}. {@code -Dcolorlight.flood.maxseeds=N}. */
    private static final int MAX_TASK_SEEDS = Integer.getInteger("colorlight.flood.maxseeds", 8192);

    /** A batch with fewer seeds than this is flooded on one thread: handing it over would cost more than it saves. */
    private static final int PARALLEL_MIN_SEEDS = Integer.getInteger("colorlight.flood.parallelseeds", 128);

    /**
     * The second flood thread (the apply thread is the first). Runs only the floods of the batch it is given, while the
     * apply thread floods the other half, both under the engine lock. Daemon, so it never keeps the game alive.
     */
    private static final ExecutorService FLOOD_HELPER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ColorLight Flood Helper");
        thread.setDaemon(true);
        return thread;
    });

    /** A batch with more sources than this gets its quick light first, the exact light a moment later. */
    private static final int FAST_LIGHT_MIN_SOURCES = Integer.getInteger("colorlight.fast.minsources", 2000);

    /** Quick light of batches whose exact light is still to come. Read by the render side, so copy-on-write. */
    private final CopyOnWriteArrayList<FastBatch> fastBatches = new CopyOnWriteArrayList<>();

    /** Propagators of the helper thread (they hold scratch memory). Used by that thread only. */
    private final Map<Identifier, LightPropagator> helperPropagators = new HashMap<>();

    /** {@code -Dcolorlight.batch.group=N} forces the group size (for measuring). */
    private static final int GROUP_OVERRIDE = Integer.getInteger("colorlight.batch.group", 0);

    private int batchGroupSize() {
        if (GROUP_OVERRIDE > 0)
            return GROUP_OVERRIDE;
        return 1024;
    }

    private static final long[] NO_KEYS = new long[0];
    private static final long TIME_FACTOR_TTL_NANOS = 50_000_000L;

    private final int maxRangeBlocks;
    protected final float decayPerOpacityUnit;
    protected final LevelAccessor level;
    /** What a source without its own override actually spreads with (setting {@code propagation}). */
    private final Identifier defaultMethodId;
    /** Method moving (entity) lights spread with (setting {@code dynamic_propagation}); must be table-driven, else the default smooth one is used. */
    private final Identifier dynamicMethodId;

    /**
     * Propagators/queues for every {@link JsonPropagationMethod} in use (the default one and whatever blocks
     * asked for; see {@link me.mrhikmen.colorlight.client.core.light.propagation}), created lazily the first time
     * that method is actually used by this engine. Only touched while holding {@link #lock}.
     */
    private final Map<Identifier, LightPropagator> customPropagators = new HashMap<>();
    /** Cells to flood again from what they store (after a removal), by the method they spread with. Guarded by {@link #lock}. */
    private final Map<Identifier, LongQueue> customQueues = new HashMap<>();

    /** What one flood spreads: one colour (palette id) with one propagation method. */
    private record JobKey(Identifier method, int id) {}

    /** Seeds of one flood: cell keys and the brightness each starts with. */
    private static final class SeedList {
        long[] keys = new long[16];
        int[] levels = new int[16];
        int size;

        void add(long key, int level) {
            if (size == keys.length) {
                keys = java.util.Arrays.copyOf(keys, size << 1);
                levels = java.util.Arrays.copyOf(levels, size << 1);
            }
            keys[size] = key;
            levels[size] = level;
            size++;
        }

        void clear() {
            size = 0;
            if (keys.length > (1 << 15)) { // don't keep the arrays of a huge batch alive
                keys = new long[16];
                levels = new int[16];
            }
        }
    }

    /** Sources (and re-seeded cells) waiting to be flooded, one list per flood. Guarded by {@link #lock}. */
    private final Map<JobKey, SeedList> jobs = new HashMap<>();
    /** key -> propagation method id, for sources whose method isn't the engine default. Guarded by {@link #lock}. */
    private final Map<Long, Identifier> customMethodByKey = new HashMap<>();

    private final LightStorage data = new LightStorage();
    /** The static sources by section; kept in step with {@link #sourceColors}. Guarded by {@link #lock}. */
    private final SourceIndex sourceIndex = new SourceIndex();
    private final DynamicLightLayer dynamic;
    /** The colours of this world's lights; ids are never reused, so stored cells stay valid. */
    private final LightPalette palette = new LightPalette();

    /** key -> the source's own (pre-spread) colour. Guarded by {@link #lock}. */
    private final LongIntMap sourceColors = new LongIntMap(256);
    /** Sections changed by the mutation currently running. Guarded by {@link #lock}; published by {@link #unlock()}. */
    private final LongIntMap dirtySections = new LongIntMap(256);
    /** The section most recently marked by {@link #markDirty} (a flood marks the same one thousands of times in a row). */
    private long lastDirtySection = Long.MIN_VALUE;
    /**
     * Sections waiting to be handed to the renderer. Guarded by its own tiny monitor, so the game thread
     * can read and add to it without ever waiting for a flood that holds {@link #lock}. Floods publish into it
     * at the end of every locked operation (see {@link #unlock()}), so light changes reach the renderer
     * promptly even while the apply thread keeps the lock busy back-to-back.
     */
    private final LongIntMap externalDirty = new LongIntMap(256);

    private final ReentrantLock lock = new ReentrantLock();

    // ---- scratch state, only touched while holding the lock ----
    private final PassMap seedSeen = new PassMap(1 << 10);
    /**
     * Set when taking light away touched a cell that had no room for one of its colours ({@link LightCell#OVERFLOW}):
     * what such a cell hides cannot be followed cell by cell, so the surroundings are rebuilt, see {@link #rebuildAround}.
     */
    private boolean rebuildNeeded;
    private final LongQueue darkenKeys = new LongQueue(1 << 10);
    private final IntQueue darkenIds = new IntQueue(1 << 10);
    private final IntQueue darkenLevels = new IntQueue(1 << 10);

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
                lastDirtySection = Long.MIN_VALUE;
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
        this(level, maxRangeBlocks, PropagationMethodRegistry.GRID, PropagationMethodRegistry.SMOOTH);
    }

    /**
     * @param defaultMethodId externally configurable id of the method plain block sources spread with; an id that is not
     *                        registered falls back to {@code colorlight:grid}
     * @param dynamicMethodId method for moving lights, see {@link #newDynamicWorker()}
     */
    public ColorLightEngine(LevelAccessor level, int maxRangeBlocks, Identifier defaultMethodId, Identifier dynamicMethodId) {
        this.level = level;
        this.maxRangeBlocks = Math.max(1, maxRangeBlocks); // защита от 0/отрицательных значений из конфига
        this.decayPerOpacityUnit = ColorLightUtil.MAX / (float) this.maxRangeBlocks;
        this.defaultMethodId = PropagationMethodRegistry.contains(defaultMethodId) ? defaultMethodId : PropagationMethodRegistry.GRID;
        this.dynamicMethodId = (dynamicMethodId != null) ? dynamicMethodId : PropagationMethodRegistry.SMOOTH;
        this.staticField = new WorldLightField(level, data, this::markDirty);
        this.dynamic = new DynamicLightLayer(this::markDirty);
    }

    // =====================================================================================
    // Simple accessors
    // =====================================================================================

    public int getMaxRangeBlocks() {
        return maxRangeBlocks;
    }

    /** The engine-wide default method (what plain block sources use). Individual sources may override it. */
    public Identifier getDefaultMethodId() {
        return defaultMethodId;
    }

    public float getDecayPerOpacityUnit() {
        return decayPerOpacityUnit;
    }

    /** Colour of a cell as the renderer sees it: static and dynamic light merged. */
    public int getColor(BlockPos pos) {
        return getColor(pos.getX(), pos.getY(), pos.getZ());
    }

    public int getColor(int x, int y, int z) {
        long cell = data.get(x, y, z) & LightCell.SLOTS;
        if (dynamic.isActive()) {
            long dyn = dynamic.storage().get(x, y, z);
            if (dyn != 0)
                cell = LightCell.merge(cell, dyn);
        }
        for (FastBatch batch : fastBatches) {
            long quick = batch.storage.get(x, y, z) & LightCell.SLOTS;
            if (quick != 0)
                cell = LightCell.merge(cell, quick);
        }
        return cell == 0 ? 0 : LightCell.toRgb(cell, palette);
    }

    /** Static (block) light only - what block edits can affect. */
    public int getStaticColor(BlockPos pos) {
        long cell = data.get(pos.getX(), pos.getY(), pos.getZ()) & LightCell.SLOTS;
        return cell == 0 ? 0 : LightCell.toRgb(cell, palette);
    }

    /** The colours the lights of this world use; the GPU needs it to turn the ids in a packed cell back into colours. */
    public LightPalette palette() {
        return palette;
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
        addSource(pos, r, g, b, strength, (Identifier) null);
    }

    /**
     * Adds a source that spreads using the propagation method {@code methodId} identifies (see
     * {@link PropagationMethodRegistry}, and {@link me.mrhikmen.colorlight.client.config.BlockSettings#propagation}
     * for where a block's own choice usually comes from), independent of the engine's default.
     * {@code null} keeps the engine's default, and an id that isn't currently registered (e.g. the pack
     * providing it is disabled) falls back to it too, so a stale config entry never breaks lighting.
     * Re-adding an identical source is a no-op; re-adding it with a different colour/model first
     * removes the old one so stale light around it is cleaned up.
     */
    public void addSource(BlockPos pos, int r, int g, int b, int strength, Identifier methodId) {
        Identifier resolved = resolveMethod(methodId);

        float scale = ColorLightUtil.clamp01(strength / 15f);
        int packed = ColorLightUtil.pack(Math.round(r * scale), Math.round(g * scale), Math.round(b * scale));

        lock.lock();
        try {
            addSourceLocked(pos.getX(), pos.getY(), pos.getZ(), packed, resolved);
        } finally {
            unlock();
        }
    }

    /** {@code null} or an unregistered id both fall back to the engine's own default method. */
    private Identifier resolveMethod(Identifier methodId) {
        if (methodId == null)
            return defaultMethodId;
        return PropagationMethodRegistry.contains(methodId) ? methodId : defaultMethodId;
    }

    /**
     * Adds many sources at once, e.g. everything found in a freshly loaded chunk - each with its own
     * propagation method ({@link SourceBatch#add(int, int, int, int, int, int, int, Identifier)}, e.g.
     * from that block's own {@code BlockSettings#getPropagationId()}), or the engine default where the
     * batch entry didn't ask for one. Sources are flooded together in one pass per group instead of one
     * flood each.
     *
     * @return true if the light field changed
     */
    public boolean addSources(SourceBatch batch) {
        if (batch == null || batch.isEmpty())
            return false;

        boolean changed = false;
        int total = batch.size();

        int groupSize = batchGroupSize();
        for (int from = 0; from < total; from += groupSize) {
            int to = Math.min(total, from + groupSize);
            lock.lock();
            try {
                clearCustomQueuesLocked();
                for (int i = from; i < to; i++) {
                    long key = batch.key(i);
                    Identifier methodId = resolveMethod(batch.method(i));
                    changed |= placeSourceLocked(PosKey.x(key), PosKey.y(key), PosKey.z(key), batch.color(i), methodId);
                }
                flushQueuesLocked();
            } finally {
                unlock();
            }
        }
        return changed;
    }

    /**
     * The exact light of a batch that was lit quickly: floods the seeds that are still sources, then drops the quick
     * light. Does nothing if the batch was dropped meanwhile (world change, unloaded chunks).
     */
    private void completeFast(FastBatch batch) {
        lock.lock();
        try {
            if (!fastBatches.remove(batch))
                return;

            clearCustomQueuesLocked();
            for (DeferredSeeds d : batch.deferred) {
                for (int i = 0; i < d.keys().length; i++) {
                    long key = d.keys()[i];
                    if ((data.get(PosKey.x(key), PosKey.y(key), PosKey.z(key)) & FLAG_SOURCE) == 0)
                        continue; // removed since it was queued
                    if (d.levels()[i] > 0)
                        seedList(d.key().method(), d.key().id()).add(key, d.levels()[i]);
                }
            }
            flushQueuesLocked();

            int[] sections = {0};
            batch.storage.forEachLitSection(key -> sections[0]++);
            ColorLightClient.LOGGER.info("[ColorLight] quick light done: {} sections, read by renderer {} times, {} ticks alive, gpu={}",
                    sections[0], batch.served, batch.age, ColorLightGpu.isActive());
            batch.storage.forEachLitSection(this::markSectionAndNeighborsDirty);
            batch.storage.clear();
        } finally {
            unlock();
        }
    }

    /**
     * Called on the game thread every tick. A quick batch keeps its quick light until the renderer has taken it: only
     * ticks in which nothing is waiting for upload count down. Then the exact flood starts (on the apply thread). A hard
     * age limit makes sure the exact light comes even if the renderer never reports idle.
     *
     * @param renderCaughtUp true when the renderer has no section waiting for upload
     */
    public void onClientTick(boolean renderCaughtUp) {
        if (fastBatches.isEmpty())
            return;
        boolean quiet = renderCaughtUp && !hasUndrainedDirty();
        for (FastBatch batch : fastBatches) {
            if (batch.scheduled)
                continue;
            batch.age++;
            if (quiet && batch.ticksLeft > 0)
                batch.ticksLeft--;
            if (batch.ticksLeft == 0 || batch.age >= FastBatch.MAX_AGE_TICKS) {
                batch.scheduled = true;
                ColorLightClient.LOGGER.info("[ColorLight] quick light: exact light scheduled after {} ticks, reason: {}",
                        batch.age, batch.ticksLeft == 0 ? "renderer idle" : "age limit");
                ColorLightChunkScanner.runOnApplyThread(() -> completeFast(batch));
            }
        }
    }

    /** Quick light of one batch of sources, with what the exact flood needs later. */
    private static final class FastBatch {
        /** Quiet ticks the renderer needs after the last upload before the exact light replaces the quick one. */
        static final int TICKS_BEFORE_EXACT = 3;
        /** About 20 seconds at 20 ticks per second: the exact light arrives even if the renderer stays busy. */
        static final int MAX_AGE_TICKS = 400;
        final LightStorage storage = new LightStorage();
        final List<DeferredSeeds> deferred = new java.util.ArrayList<>();
        int ticksLeft = TICKS_BEFORE_EXACT;
        int age;
        boolean scheduled;
        /** How often the renderer read a section through this batch (diagnostics only, racy by design). */
        long served;

        boolean hasSources() {
            return !deferred.isEmpty();
        }
    }

    /** The seeds of one job as they were queued, kept until the exact flood runs. */
    private record DeferredSeeds(JobKey key, long[] keys, int[] levels) {}

    private void addSourceLocked(int x, int y, int z, int packed, Identifier methodId) {
        clearCustomQueuesLocked();
        placeSourceLocked(x, y, z, packed, methodId);
        flushQueuesLocked();
    }

    /** Writes the source cell and queues it as a seed; the caller floods afterwards. @return whether anything changed */
    private boolean placeSourceLocked(int x, int y, int z, int packed, Identifier methodId) {
        long key = PosKey.pack(x, y, z);
        long flags = FLAG_SOURCE | methodFlags(methodId);

        long existing = data.get(x, y, z);
        if ((existing & FLAG_SOURCE) != 0) {
            boolean sameFlags = (existing & ENGINE_FLAGS) == flags;
            boolean sameMethod = sameFlags && ((flags & FLAG_CUSTOM) == 0 || methodId.equals(customMethodByKey.get(key)));
            if (sameMethod && sourceColors.get(key, -1) == packed)
                return false; // identical source already there

            // different colour/strength/model: clean up what the old one lit before adding the new one
            flushQueuesLocked(); // don't lose seeds already queued for this batch group
            removeSourceLocked(x, y, z);
            existing = data.get(x, y, z);
        }

        sourceColors.put(key, packed);
        sourceIndex.add(key);
        if ((flags & FLAG_CUSTOM) != 0) {
            customMethodByKey.put(key, methodId);
        } else {
            customMethodByKey.remove(key); // in case this position previously held a custom-method source
        }
        sourceVersion++;

        // the light the source itself gives: its brightest channel as the brightness, its hue as the colour
        int level = LightPalette.peakOf(packed);
        int id = palette.idFor(packed);
        long merged = LightCell.insert(existing & (LightCell.SLOTS | LightCell.OVERFLOW), id, level);
        data.put(x, y, z, flags | merged);
        markDirty(x, y, z);

        if (level > 0)
            seedList(methodId, id).add(key, level);
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
        long cell = data.get(x, y, z);
        if ((cell & FLAG_SOURCE) == 0)
            return;

        sourceColors.remove(key);
        sourceIndex.remove(key);
        customMethodByKey.remove(key);
        sourceVersion++;

        long old = cell & LightCell.SLOTS;
        data.put(x, y, z, 0L);
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

            long cell = data.get(x, y, z);
            if ((cell & FLAG_SOURCE) != 0)
                return;
            if (!hasStaticLightNear(x, y, z))
                return;

            // Remember the light around the block: if the re-flood ends with exactly the same light (a block that
            // only looked like it could matter), nothing is published as dirty and nothing visibly rebuilds.
            int radius = Math.min(maxRangeBlocks, 24) + 1;
            boolean snapshot = dirtySections.isEmpty();
            if (snapshot)
                snapshotRegion(x, y, z, radius);

            long key = PosKey.pack(x, y, z);
            long old = cell & LightCell.SLOTS;
            data.put(x, y, z, 0L);
            if (old != 0)
                markDirty(x, y, z);

            darkenAndCollectSeeds(key, old);

            seedOnce(defaultMethodId, key);
            for (int n = 0; n < NEIGHBOR_COUNT; n++) {
                int nx = x + NEIGHBOR_DX[n], ny = y + NEIGHBOR_DY[n], nz = z + NEIGHBOR_DZ[n];
                long nCell = data.get(nx, ny, nz);
                if (!LightCell.isLit(nCell) && (nCell & FLAG_SOURCE) == 0)
                    continue; // nothing there that could spread into the changed cell
                long nKey = PosKey.pack(nx, ny, nz);
                seedOnce(methodIdOf(nCell, nKey), nKey);
            }

            flushQueuesLocked();

            if (snapshot && regionUnchanged(x, y, z, radius)) {
                dirtySections.clear();
                lastDirtySection = Long.MIN_VALUE;
            }
        } finally {
            unlock();
        }
    }

    private long[] regionSnapshot = new long[0];

    private void snapshotRegion(int cx, int cy, int cz, int r) {
        int side = 2 * r + 1;
        if (regionSnapshot.length < side * side * side)
            regionSnapshot = new long[side * side * side];
        int i = 0;
        for (int y = cy - r; y <= cy + r; y++)
            for (int z = cz - r; z <= cz + r; z++)
                for (int x = cx - r; x <= cx + r; x++)
                    regionSnapshot[i++] = data.get(x, y, z);
    }

    private boolean regionUnchanged(int cx, int cy, int cz, int r) {
        int i = 0;
        for (int y = cy - r; y <= cy + r; y++)
            for (int z = cz - r; z <= cz + r; z++)
                for (int x = cx - r; x <= cx + r; x++)
                    if (regionSnapshot[i++] != data.get(x, y, z))
                        return false;
        return true;
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
                    if (LightCell.isLit(data.get(x + dx, y + dy, z + dz)))
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
            fastBatches.clear();
            sourceColors.clear();
            sourceIndex.clear();
            customMethodByKey.clear();
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
            for (FastBatch batch : fastBatches)
                batch.storage.removeColumn(PosKey.x(key), PosKey.z(key));
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
                sourceIndex.remove(holder[0][i]);
                customMethodByKey.remove(holder[0][i]);
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

    /** Which flag bit(s) to set on a source cell for {@code methodId} - none for the default method, see {@link #FLAG_CUSTOM}. */
    private long methodFlags(Identifier methodId) {
        if (methodId == null || defaultMethodId.equals(methodId))
            return 0;
        return FLAG_CUSTOM;
    }

    /** The propagation method a stored cell actually spreads with; {@code key} is only consulted for a non-default source. */
    private Identifier methodIdOf(long cell, long key) {
        if ((cell & FLAG_SOURCE) == 0)
            return defaultMethodId; // an ordinary (non-source) cell always falls back to the engine default
        if ((cell & FLAG_CUSTOM) != 0)
            return customMethodByKey.getOrDefault(key, defaultMethodId);
        return defaultMethodId;
    }

    /** The propagator for {@code methodId}, created and cached on first use. Call only while holding {@link #lock}. */
    private LightPropagator propagatorFor(Identifier methodId) {
        return customPropagators.computeIfAbsent(methodId == null ? defaultMethodId : methodId, this::newPropagator);
    }

    /** A fresh propagator for a method (they hold scratch memory, so every thread needs its own). Thread-safe. */
    private LightPropagator newPropagator(Identifier id) {
        JsonPropagationMethod method = PropagationMethodRegistry.get(id);
        if (method == null)
            method = PropagationMethodRegistry.get(defaultMethodId); // unregistered in the meantime
        if (method == null)
            method = PropagationMethodRegistry.get(PropagationMethodRegistry.GRID);
        return method.create(decayPerOpacityUnit);
    }

    /** The re-seed queue for {@code methodId}, created on first use. Call only while holding {@link #lock}. */
    private LongQueue queueFor(Identifier methodId) {
        return customQueues.computeIfAbsent(methodId == null ? defaultMethodId : methodId, id -> new LongQueue(1 << 8));
    }

    /** The seeds of the flood of one colour with one method, created on first use. Call only while holding {@link #lock}. */
    private SeedList seedList(Identifier methodId, int id) {
        return jobs.computeIfAbsent(new JobKey(methodId == null ? defaultMethodId : methodId, id), k -> new SeedList());
    }

    private void clearCustomQueuesLocked() {
        for (LongQueue queue : customQueues.values())
            queue.clear();
        for (SeedList list : jobs.values())
            list.clear();
    }

    private void flushQueuesLocked() {
        // cells to re-flood: every light a cell stores spreads on from its stored brightness
        if (!customQueues.isEmpty()) {
            for (Map.Entry<Identifier, LongQueue> entry : customQueues.entrySet()) {
                LongQueue queue = entry.getValue();
                Identifier method = entry.getKey();
                while (!queue.isEmpty()) {
                    long key = queue.poll();
                    long cell = data.get(PosKey.x(key), PosKey.y(key), PosKey.z(key));
                    int a = LightCell.levelA(cell);
                    if (a != 0)
                        seedList(method, LightCell.idA(cell)).add(key, a);
                    int b = LightCell.levelB(cell);
                    if (b != 0)
                        seedList(method, LightCell.idB(cell)).add(key, b);
                }
            }
        }
        if (!jobs.isEmpty())
            floodJobsLocked();
    }

    private void floodJobsLocked() {
        // each colour is spread on its own, so overlapping lights only meet where they reach the same cell
        List<FloodTask> tasks = new java.util.ArrayList<>();
        int seeds = buildTasks(tasks);

        if (tasks.size() >= 2 && seeds >= PARALLEL_MIN_SEEDS) {
            floodTasksInParallelLocked(tasks);
        } else {
            for (FloodTask task : tasks)
                floodTaskLocked(task);
        }

        for (SeedList list : jobs.values())
            list.clear();
    }

    /**
     * Two threads flood the tasks of one batch: this one and {@link #FLOOD_HELPER}. The tasks are split into two halves
     * of similar work. A flood reads block opacity and writes only its own working values, so it can run without the
     * storage; each one records its raises, and the records are replayed into the storage in task order, exactly the
     * order the one-thread path writes them in. So the result does not depend on how the tasks were split.
     * If the helper fails, its tasks are flooded here, after the replay of the ones before them.
     */
    private void floodTasksInParallelLocked(List<FloodTask> tasks) {
        int n = tasks.size();
        boolean[] onHelper = new boolean[n];
        long here = 0, there = 0;
        for (int i = 0; i < n; i++) {
            int size = tasks.get(i).size();
            if (there < here) {
                onHelper[i] = true;
                there += size;
            } else {
                here += size;
            }
        }

        DeferredLightField[] logs = new DeferredLightField[n];
        Future<?> helper = FLOOD_HELPER.submit(() -> {
            for (int i = 0; i < n; i++) {
                if (!onHelper[i])
                    continue;
                FloodTask task = tasks.get(i);
                DeferredLightField log = new DeferredLightField(level);
                helperPropagators.computeIfAbsent(task.method() == null ? defaultMethodId : task.method(), this::newPropagator)
                        .spread(task.id(), copyKeys(task), copyLevels(task), task.size(), log);
                logs[i] = log; // only once the flood is complete
            }
        });

        for (int i = 0; i < n; i++) {
            if (onHelper[i])
                continue;
            FloodTask task = tasks.get(i);
            DeferredLightField log = new DeferredLightField(level);
            propagatorFor(task.method()).spread(task.id(), copyKeys(task), copyLevels(task), task.size(), log);
            logs[i] = log;
        }

        boolean interrupted = false;
        boolean helperFailed = false;
        while (true) {
            try {
                helper.get();
                break;
            } catch (InterruptedException e) {
                interrupted = true; // keep waiting: the helper still reads the batch
            } catch (ExecutionException e) {
                ColorLightClient.LOGGER.error("[ColorLight] Flood helper failed, flooding its share here: {}", e.getCause().toString());
                helperFailed = true;
                break;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        for (int i = 0; i < n; i++) {
            if (logs[i] != null)
                logs[i].replayInto(staticField);
            else if (helperFailed)
                floodTaskLocked(tasks.get(i));
        }
    }

    private static long[] copyKeys(FloodTask task) {
        return java.util.Arrays.copyOfRange(task.keys(), task.from(), task.to());
    }

    private static int[] copyLevels(FloodTask task) {
        return java.util.Arrays.copyOfRange(task.levels(), task.from(), task.to());
    }

    private void floodTaskLocked(FloodTask task) {
        long[] keys = java.util.Arrays.copyOfRange(task.keys(), task.from(), task.to());
        int[] levels = java.util.Arrays.copyOfRange(task.levels(), task.from(), task.to());
        propagatorFor(task.method()).spread(task.id(), keys, levels, keys.length, staticField);
    }

    /**
     * Cuts the queued jobs into floods: the seeds of one colour that lie in the same chunk column are flooded <b>together</b>.
     * One flood with many seeds costs about as much as the area it lights, however many seeds start it (neighbouring lamps
     * overlap almost completely), while a flood per lamp pays for that area over and over - a chunk full of lamps took
     * hundreds of times longer. Seeds in different columns stay apart: lamps far from each other in one flood make its
     * scratch maps huge and cache-unfriendly.
     *
     * @return the number of seeds
     */
    private int buildTasks(List<FloodTask> out) {
        int total = 0;
        for (Map.Entry<JobKey, SeedList> entry : jobs.entrySet()) {
            SeedList list = entry.getValue();
            int n = list.size;
            if (n == 0)
                continue;
            total += n;

            Identifier method = entry.getKey().method();
            int id = entry.getKey().id();
            long[] keys = list.keys;
            int[] levels = list.levels;

            if (n < 2 || n >= (1 << 20)) {
                FloodTask.addSlices(out, method, id, keys, levels, 0, n, MAX_TASK_SEEDS);
                continue;
            }

            long[] order = new long[n]; // (column << 20) | index: sorts the seeds by chunk column
            for (int i = 0; i < n; i++) {
                long k = keys[i];
                long column = (((long) (PosKey.x(k) >> 4) & 0x3FFFFFL) << 22) | ((long) (PosKey.z(k) >> 4) & 0x3FFFFFL);
                order[i] = (column << 20) | i;
            }
            java.util.Arrays.sort(order);

            long[] sortedKeys = new long[n];
            int[] sortedLevels = new int[n];
            for (int i = 0; i < n; i++) {
                int from = (int) (order[i] & 0xFFFFFL);
                sortedKeys[i] = keys[from];
                sortedLevels[i] = levels[from];
            }

            int start = 0;
            for (int i = 1; i <= n; i++) {
                if (i == n || (order[i] >>> 20) != (order[start] >>> 20)) {
                    FloodTask.addSlices(out, method, id, sortedKeys, sortedLevels, start, i, MAX_TASK_SEEDS);
                    start = i;
                }
            }
        }
        return total;
    }

    /**
     * The world's static light as the propagators see it: values live in {@link #data}, opacity is read from the level
     * once per cell per pass, and every stored change is recorded as dirty for the renderer.
     * Only used while holding {@link #lock}.
     */
    private final WorldLightField staticField;

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
    private void darkenAndCollectSeeds(long startKey, long oldCell) {
        darkenCells(startKey, oldCell);
        recoverHiddenLight(startKey);
    }

    private void darkenCells(long startKey, long oldCell) {
        clearCustomQueuesLocked();
        darkenKeys.clear();
        darkenIds.clear();
        darkenLevels.clear();
        seedSeen.clear();
        rebuildNeeded = false;

        if (!LightCell.isLit(oldCell))
            return;

        // each light the removed cell held is taken away on its own
        int a = LightCell.levelA(oldCell);
        if (a != 0) {
            darkenKeys.add(startKey);
            darkenIds.add(LightCell.idA(oldCell));
            darkenLevels.add(a);
        }
        int b = LightCell.levelB(oldCell);
        if (b != 0) {
            darkenKeys.add(startKey);
            darkenIds.add(LightCell.idB(oldCell));
            darkenLevels.add(b);
        }

        while (!darkenKeys.isEmpty()) {
            long curKey = darkenKeys.poll();
            int id = darkenIds.poll();
            int v = darkenLevels.poll();

            int x = PosKey.x(curKey), y = PosKey.y(curKey), z = PosKey.z(curKey);

            for (int n = 0; n < NEIGHBOR_COUNT; n++) {
                int nx = x + NEIGHBOR_DX[n], ny = y + NEIGHBOR_DY[n], nz = z + NEIGHBOR_DZ[n];
                long nCell = data.get(nx, ny, nz);
                int nl = LightCell.levelOf(nCell, id);
                long nKey = PosKey.pack(nx, ny, nz);

                if ((nCell & FLAG_SOURCE) != 0) {
                    int own = sourceColors.get(nKey, 0);
                    int ownLevel = LightPalette.peakOf(own);
                    int ownId = ownLevel > 0 ? palette.idFor(own) : 0;

                    // brightness above the source's own but below what was removed was fed by the removed light
                    int target = nl;
                    if (nl > 0 && nl < v) {
                        if (id == ownId) {
                            if (nl > ownLevel)
                                target = ownLevel;
                        } else {
                            target = 0; // not the source's own colour: nothing of it is the source's own
                        }
                    }

                    if (target != nl) {
                        recoverIfHidden(nCell, nKey);
                        data.put(nx, ny, nz, LightCell.withLevel(nCell, id, target));
                        markDirty(nx, ny, nz);
                        darkenKeys.add(nKey);
                        darkenIds.add(id);
                        darkenLevels.add(nl);
                    }
                    seedOnce(methodIdOf(nCell, nKey), nKey);
                    continue;
                }

                if (nl == 0) {
                    recoverIfHidden(nCell, nKey); // it may have passed the colour on without keeping it
                    continue;
                }

                // a dim cell within one unit of the removed value counts as fed by it too: smooth light is stored rounded,
                // so the far fringe of a light can come out equal to (or one above) its parent and would otherwise stay
                // behind as a faint ghost glow after the light is removed
                if (nl < v || (nl <= v + 1 && nl <= 8)) {
                    recoverIfHidden(nCell, nKey);
                    data.put(nx, ny, nz, LightCell.withLevel(nCell, id, 0));
                    markDirty(nx, ny, nz);
                    darkenKeys.add(nKey);
                    darkenIds.add(id);
                    darkenLevels.add(nl);
                }

                // independent light of this colour that is at least as bright -> must radiate again
                if (v > 0 && nl >= v)
                    seedOnce(defaultMethodId, nKey);
            }
        }
    }

    private void seedOnce(Identifier methodId, long key) {
        if (seedSeen.contains(key))
            return;
        seedSeen.put(key, 1L);
        queueFor(methodId).add(key);
        // what it stores is all it can spread on; a light it had no room for has to come from the sources again
        recoverIfHidden(data.get(PosKey.x(key), PosKey.y(key), PosKey.z(key)), key);
    }

    /**
     * A cell that had no room for a third colour ({@link LightCell#OVERFLOW}) may have passed that colour on without
     * keeping it, so taking light away around it cannot be followed cell by cell any more: note that the surroundings
     * need a rebuild.
     */
    private void recoverIfHidden(long cell, long key) {
        if ((cell & LightCell.OVERFLOW) != 0)
            rebuildNeeded = true;
    }

    /**
     * Rebuilds everything the removed or changed cell could have influenced: the cells within light range of it are
     * emptied, then every source that can reach them floods again. Only done where cells overflowed, which is rare
     * (three or more colours meeting), so the price of the brute force is paid seldom.
     */
    private void recoverHiddenLight(long centerKey) {
        if (!rebuildNeeded)
            return;
        rebuildNeeded = false;

        int cx = PosKey.x(centerKey), cy = PosKey.y(centerKey), cz = PosKey.z(centerKey);
        int radius = maxRangeBlocks + 1;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - radius; y <= cy + radius; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    long cell = data.get(x, y, z);
                    if ((cell & (LightCell.SLOTS | LightCell.OVERFLOW)) == 0)
                        continue;
                    data.put(x, y, z, cell & ENGINE_FLAGS);
                    markDirty(x, y, z);
                }
            }
        }

        // every source whose light can reach that box
        sourceIndex.forEachNear(cx, cy, cz, 2 * radius, sourceKey -> {
            int own = sourceColors.get(sourceKey, 0);
            int level = LightPalette.peakOf(own);
            if (level <= 0)
                return;
            int sx = PosKey.x(sourceKey), sy = PosKey.y(sourceKey), sz = PosKey.z(sourceKey);
            long cell = data.get(sx, sy, sz);
            int ownId = palette.idFor(own);
            long restored = LightCell.insert(cell, ownId, level);
            if (restored != cell) {
                data.put(sx, sy, sz, restored);
                markDirty(sx, sy, sz);
            }
            seedList(methodIdOf(cell, sourceKey), ownId).add(sourceKey, level);
        });
    }

    private void markDirty(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx != 0 && lx != 15 && ly != 0 && ly != 15 && lz != 0 && lz != 15) {
            // not on a section border: only this section is affected, and a flood marks it over and over
            long key = PosKey.pack(sx, sy, sz);
            if (key == lastDirtySection)
                return;
            lastDirtySection = key;
            dirtySections.put(key, 1);
            return;
        }
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

    /** Calls {@code consumer} with a packed {@link PosKey} for every section that holds light (static or moving). */
    public void forEachLitSection(java.util.function.LongConsumer consumer) {
        data.forEachLitSection(consumer);
        if (dynamic.isActive())
            dynamic.storage().forEachLitSection(consumer);
        for (FastBatch batch : fastBatches)
            batch.storage.forEachLitSection(consumer);
    }

    /** {@link #forEachLitSection} restricted to sections within the radii of a centre section (cheap; for periodic rescans). */
    public void forEachLitSectionNear(int csx, int csy, int csz, int radiusXZ, int radiusY, java.util.function.LongConsumer consumer) {
        data.forEachLitSectionNear(csx, csy, csz, radiusXZ, radiusY, consumer);
        if (dynamic.isActive())
            dynamic.storage().forEachLitSectionNear(csx, csy, csz, radiusXZ, radiusY, consumer);
        for (FastBatch batch : fastBatches)
            batch.storage.forEachLitSectionNear(csx, csy, csz, radiusXZ, radiusY, consumer);
    }

    /** True while changed sections are still waiting in the queue that the renderer drains. */
    public boolean hasUndrainedDirty() {
        synchronized (externalDirty) {
            return !externalDirty.isEmpty();
        }
    }

    private static final ThreadLocal<long[]> SECTION_SCRATCH = ThreadLocal.withInitial(() -> new long[16 * 16 * 16]);

    /**
     * Fills {@code out} (4096 ints, index {@code (y << 8) | (z << 4) | x}) with the cells of one section as the GPU
     * reads them: static and moving light merged, each cell packed by {@link LightCell#pack}. Lock-free.
     *
     * @return false if the section holds no light at all
     */
    public boolean copySectionPacked(int sx, int sy, int sz, int[] out) {
        long[] cells = SECTION_SCRATCH.get();
        boolean lit = data.copySection(sx, sy, sz, cells);
        if (dynamic.isActive())
            lit |= dynamic.storage().mergeInto(sx, sy, sz, cells);
        for (FastBatch batch : fastBatches)
            lit |= mergeFast(batch, sx, sy, sz, cells);
        if (!lit) {
            java.util.Arrays.fill(out, 0, cells.length, 0);
            return false;
        }
        for (int i = 0; i < cells.length; i++)
            out[i] = LightCell.pack(cells[i], palette);
        return true;
    }

    /**
     * Like {@link #copySectionPacked} but with the colours the cells show ({@code 0x00BBGGRR}), for code that wants
     * plain colours.
     */
    public boolean copySectionColors(int sx, int sy, int sz, int[] out) {
        long[] cells = SECTION_SCRATCH.get();
        boolean lit = data.copySection(sx, sy, sz, cells);
        if (dynamic.isActive())
            lit |= dynamic.storage().mergeInto(sx, sy, sz, cells);
        for (FastBatch batch : fastBatches)
            lit |= mergeFast(batch, sx, sy, sz, cells);
        if (!lit) {
            java.util.Arrays.fill(out, 0, cells.length, 0);
            return false;
        }
        for (int i = 0; i < cells.length; i++)
            out[i] = cells[i] == 0 ? 0 : LightCell.toRgb(cells[i], palette);
        return true;
    }

    /** Like {@link #copySectionColors} but only the static light (no moving lights). */
    public boolean copyStaticSection(int sx, int sy, int sz, int[] out) {
        long[] cells = SECTION_SCRATCH.get();
        boolean lit = data.copySection(sx, sy, sz, cells);
        for (FastBatch batch : fastBatches)
            lit |= mergeFast(batch, sx, sy, sz, cells);
        for (int i = 0; i < cells.length; i++)
            out[i] = cells[i] == 0 ? 0 : LightCell.toRgb(cells[i], palette);
        return lit;
    }

    /** Merges one quick batch into a section copy for the renderer, counting the reads for the log. */
    private static boolean mergeFast(FastBatch batch, int sx, int sy, int sz, long[] cells) {
        boolean lit = batch.storage.mergeInto(sx, sy, sz, cells);
        if (lit)
            batch.served++;
        return lit;
    }

    /** Hands every section that holds light, and its neighbours, to the mesh-rebuild queue. Never waits for a flood. */
    public void markEverythingDirty() {
        List<Long> keys = new java.util.ArrayList<>();
        forEachLitSection(keys::add);
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
    // Dynamic (entity) lights
    // =====================================================================================

    /**
     * Creates a helper that computes dynamic-light footprints off the game thread. Give each
     * worker thread its own instance.
     */
    public DynamicLightWorker newDynamicWorker() {
        return newDynamicWorker(null);
    }

    /** Like {@link #newDynamicWorker()}, spreading with {@code methodId} instead of the moving-light method (null = that one). */
    public DynamicLightWorker newDynamicWorker(Identifier methodId) {
        return new DynamicLightWorker(new DynamicFlood(level, palette, decayPerOpacityUnit, methodId != null ? methodId : dynamicMethodId, defaultMethodId));
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
            DynamicFootprint footprint = flood.compute(x - 0.5, y, z - 0.5, r, g, b, strength);
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

    /** What the new engine holds in one cell, for {@code /colorlight cell}: slots, flags, the word the GPU gets. */
    public String debugCell(int x, int y, int z) {
        long st = data.get(x, y, z);
        long dyn = dynamic.isActive() ? dynamic.storage().get(x, y, z) : 0L;
        long both = LightCell.merge(st & LightCell.SLOTS, dyn);
        int word = LightCell.pack(both, palette);
        StringBuilder sb = new StringBuilder();
        sb.append("cell ").append(x).append(' ').append(y).append(' ').append(z).append('\n');
        sb.append("static : ").append(describe(st)).append((st & FLAG_SOURCE) != 0 ? "  [source]" : "").append('\n');
        sb.append("dynamic: ").append(describe(dyn)).append('\n');
        sb.append("GPU word: 0x").append(String.format("%08X", word));
        if ((word & LightCell.PACKED_TWO_LIGHTS_BIT) != 0)
            sb.append("  two lights: I=").append(word & 0xFF).append(" id1=").append((word >> 8) & 0xFF)
                    .append(" id2=").append((word >> 16) & 0xFF).append(" ratio=").append((word >> 24) & 31).append("/31");
        else
            sb.append("  one light: baked colour");
        sb.append("\n  ")
                .append("  colour=0x").append(String.format("%06X", LightCell.packedToRgb(word, palette))).append('\n');
        sb.append("palette: ").append(palette.size()).append(" of ").append(LightPalette.MAX_ID).append(" hues (v").append(palette.version()).append(')');
        return sb.toString();
    }

    private String describe(long cell) {
        StringBuilder sb = new StringBuilder();
        appendSlot(sb, "A", LightCell.idA(cell), LightCell.levelA(cell));
        sb.append("  ");
        appendSlot(sb, "B", LightCell.idB(cell), LightCell.levelB(cell));
        if ((cell & LightCell.OVERFLOW) != 0)
            sb.append("  OVERFLOW");
        return sb.toString();
    }

    private void appendSlot(StringBuilder sb, String name, int id, int level) {
        if (level == 0) {
            sb.append(name).append(": -");
            return;
        }
        sb.append(name).append(": id ").append(id).append(" (hue 0x").append(String.format("%06X", palette.rgbOf(id)))
                .append(") level ").append(level);
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

        return "dayTimeRaw=" + dayTimeRaw + " dayTime%24000=" + dayTime + " rawSky=" + rawSky + " skyExposure=" + skyExposure + " timeFactor=" + timeFactor + " finalDaylightFactor=" + finalFactor + " defaultPropagationMethod=" + defaultMethodId;
    }

    public int sampleFlatColor(BlockPos pos, net.minecraft.core.Direction face) {
        BlockPos facePos = (face != null) ? pos.relative(face) : pos;
        return ColorLightUtil.max(getColor(pos), getColor(facePos));
    }
}
