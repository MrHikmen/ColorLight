package me.mrhikmen.colorlight.core.light.engine;

import me.mrhikmen.colorlight.core.light.color.ColorLightUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ColorLightEngine {

    protected static final int VANILLA_MAX_OPACITY = 15;

    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * A single propagation edge: the coordinate offset of a neighbor and how
     * far away it actually is. For {@link ColorLightPropagationMode#GRID} this
     * is always exactly 1 (axis-aligned hop); for
     * {@link ColorLightPropagationMode#SMOOTH} diagonal neighbors carry their
     * true Euclidean distance so decay scales correctly with how far the light
     * actually travelled.
     */
    private record Neighbor(int dx, int dy, int dz, float distance) {
    }

    /** Every light source remembers its own color plus which model spread it. */
    private record SourceState(int color, ColorLightPropagationMode mode) {
    }

    /**
     * The two independently-flooded relight queues produced by a single darken
     * pass: cells that need to be re-lit via {@link ColorLightPropagationMode#GRID}
     * and cells that need {@link ColorLightPropagationMode#SMOOTH}. Kept apart so
     * that re-lighting after a block edit never blurs a classic block light into
     * a round one, or vice versa.
     */
    private record RelightPlan(LongQueue gridSeeds, LongQueue smoothSeeds) {
    }

    private static final Neighbor[] GRID_NEIGHBORS = buildGridNeighbors();
    private static final Neighbor[] SMOOTH_NEIGHBORS = buildSmoothNeighbors();

    /** 1 / 0.125 — the finest sub-unit step {@link ColorLightPropagationMode#SMOOTH} tracks internally. */
    private static final int FIXED_POINT_SCALE = 8;

    /** How many sub-positions (1/8 = 0.125 of a block) {@link #addBlendedSource} snaps a continuous coordinate to, per axis. */
    private static final int POSITION_SUBDIVISIONS = 8;

    private static Neighbor[] buildGridNeighbors() {
        Neighbor[] result = new Neighbor[DIRECTIONS.length];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            Direction d = DIRECTIONS[i];
            result[i] = new Neighbor(d.getStepX(), d.getStepY(), d.getStepZ(), 1f);
        }
        return result;
    }

    private static Neighbor[] buildSmoothNeighbors() {
        List<Neighbor> list = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0)
                        continue;
                    float distance = (float) Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                    list.add(new Neighbor(dx, dy, dz, distance));
                }
            }
        }
        return list.toArray(new Neighbor[0]);
    }

    private final int maxRangeBlocks;

    protected final float decayPerOpacityUnit;

    protected final LightStorage data = new LightStorage();
    protected final ConcurrentHashMap<Long, SourceState> sources = new ConcurrentHashMap<>();

    protected final LevelAccessor level;

    /**
     * Default model used for sources that don't specify their own (regular
     * blocks, via {@link #addSource(BlockPos, int, int, int, int)}) and as the
     * fallback for generic, non-source cells re-lit after a block edit — see
     * {@link #darkenAndCollectSeeds}.
     */
    private final ColorLightPropagationMode propagationMode;

    public ColorLightEngine(LevelAccessor level, int maxRangeBlocks) {
        this(level, maxRangeBlocks, ColorLightPropagationMode.GRID);
    }

    public ColorLightEngine(LevelAccessor level, int maxRangeBlocks, ColorLightPropagationMode propagationMode) {
        this.level = level;
        this.maxRangeBlocks = Math.max(1, maxRangeBlocks); // защита от 0/отрицательных значений из конфига
        this.decayPerOpacityUnit = ColorLightUtil.MAX / (float) this.maxRangeBlocks;
        this.propagationMode = (propagationMode != null) ? propagationMode : ColorLightPropagationMode.GRID;
    }

    public int getMaxRangeBlocks() {
        return maxRangeBlocks;
    }

    /** The engine-wide default model (what plain block sources use). Individual sources may override it — see {@link #addSource(BlockPos, int, int, int, int, ColorLightPropagationMode)}. */
    public ColorLightPropagationMode getPropagationMode() {
        return propagationMode;
    }

    public int getColor(BlockPos pos) {
        return getRaw(pos.asLong());
    }

    public boolean hasSource(BlockPos pos) {
        return sources.containsKey(pos.asLong());
    }

    public boolean isOpaque(BlockPos pos) {
        return getOpacity(pos) >= VANILLA_MAX_OPACITY;
    }

    public List<BlockPos> getSourcePositions() {
        List<BlockPos> result = new ArrayList<>();
        for (Long key : sources.keySet()) {
            result.add(BlockPos.of(key));
        }
        return result;
    }

    public void clearAll() {
        data.clear();
        sources.clear();
    }

    protected int getRaw(long key) {
        return data.get(key);
    }

    public float getDecayPerOpacityUnit() {
        return decayPerOpacityUnit;
    }

    /**
     * Rough upper bound on how many nodes a single flood-fill from one source
     * is likely to touch, used only to size the initial ring-buffer capacity
     * so we grow() less often. SMOOTH has ~4x the edges per node of GRID, so
     * it gets a proportionally larger head start.
     */
    private int initialQueueCapacity(ColorLightPropagationMode mode) {
        int perRangeUnit = (mode == ColorLightPropagationMode.SMOOTH) ? 24 : 12;
        int estimate = (maxRangeBlocks + 1) * perRangeUnit;
        return Math.min(4096, Math.max(16, estimate));
    }

    /** Adds a source using the engine's default model (plain blocks). */
    public void addSource(BlockPos pos, int r, int g, int b, int strength) {
        addSource(pos, r, g, b, strength, propagationMode);
    }

    /**
     * Adds a source that spreads using {@code mode} specifically, independent
     * of the engine's default — e.g. an LDL-tracked entity can glow with a
     * round {@link ColorLightPropagationMode#SMOOTH} halo while ordinary block
     * light around it stays the classic {@link ColorLightPropagationMode#GRID}
     * diamond. Both kinds of light share the same underlying field and blend
     * correctly (brighter channel always wins), since only the flood-fill
     * shape differs, not the storage.
     */
    public void addSource(BlockPos pos, int r, int g, int b, int strength, ColorLightPropagationMode mode) {
        ColorLightPropagationMode resolvedMode = (mode != null) ? mode : propagationMode;

        float scale = ColorLightUtil.clamp01(strength / 15f);
        int packed = ColorLightUtil.pack(Math.round(r * scale), Math.round(g * scale), Math.round(b * scale));

        addSourceRaw(pos, packed, resolvedMode);
    }

    /**
     * Adds a light source at a continuous, sub-block position instead of a
     * single block coordinate — used for entity-tracked light (e.g. LDL
     * compat) so it doesn't only visibly move when the entity crosses a whole
     * block boundary. X/Z are snapped to the nearest 1/8th of a block (0.125)
     * and each active corner's brightness is set directly from its real
     * Euclidean distance to the entity's exact position — so an entity at
     * (0.0, y, 0.0) and one at (0.125, y, 0.125) genuinely light neighboring
     * blocks differently, without a big dip in between (see below), and
     * without only updating when a whole-block boundary is crossed.
     * <p>
     * Two things worth calling out about the shape of this:
     * <ul>
     *     <li><b>Distance-based, not weight-product-based.</b> An earlier
     *     version derived each corner's brightness by multiplying its
     *     per-axis trilinear weights together (as if splitting one fixed
     *     amount of "light energy" between corners). That collapses hard
     *     between lattice points — e.g. at the exact diagonal midpoint of a
     *     cell only ~57% of the peak brightness survived even after boosting
     *     — which read as the light visibly fading out mid-step and then
     *     reappearing. Computing each active corner's brightness straight
     *     from {@code baseBrightness - realDistanceToEntity * decayPerOpacityUnit}
     *     instead keeps every corner within a cell close to full strength,
     *     since the entity is never more than ~1 block from any of them.</li>
     *     <li><b>Only X/Z get split, not Y.</b> Most tracked entities (mobs,
     *     players, dropped items) sit on the ground, so a separate vertical
     *     corner rarely adds anything visible — it would just double the
     *     number of flood-fills below for no real gain. Y still feeds into
     *     the real-distance decay above, it just doesn't get its own corner.</li>
     * </ul>
     * Combined with skipping corners whose decayed brightness rounds down to
     * nothing, this keeps the common case (entity roughly aligned to the
     * grid) down to 1 flood-fill and the worst case (exactly between 4
     * corners) at 4 — half of the up-to-8 the previous version always ran —
     * which matters a lot given this can re-run on every tick a tracked
     * entity moves at least 1/8 of a block.
     * <p>
     * The caller must later call {@link #removeSource} on every
     * {@link BlockPos} in the returned list (e.g. when the entity moves to a
     * new sub-position or despawns) — {@link #addBlendedSource} itself never
     * removes anything.
     */
    public List<BlockPos> addBlendedSource(double x, double y, double z, int r, int g, int b, int strength, ColorLightPropagationMode mode) {
        ColorLightPropagationMode resolvedMode = (mode != null) ? mode : propagationMode;
        float scale = ColorLightUtil.clamp01(strength / 15f);

        float baseR = r * scale;
        float baseG = g * scale;
        float baseB = b * scale;

        int iy = (int) Math.floor(y);

        long ex = Math.round(x * POSITION_SUBDIVISIONS);
        long ez = Math.round(z * POSITION_SUBDIVISIONS);

        long ix = Math.floorDiv(ex, POSITION_SUBDIVISIONS);
        long iz = Math.floorDiv(ez, POSITION_SUBDIVISIONS);

        int fx = (int) (ex - ix * POSITION_SUBDIVISIONS);
        int fz = (int) (ez - iz * POSITION_SUBDIVISIONS);

        List<BlockPos> touched = new ArrayList<>(4);
        LongQueue queue = new LongQueue(initialQueueCapacity(resolvedMode));

        for (int dx = 0; dx <= 1; dx++) {
            if (dx == 1 && fx == 0)
                continue; // exactly aligned on X — the ceil corner would just duplicate coverage

            long cornerX = ix + dx;

            for (int dz = 0; dz <= 1; dz++) {
                if (dz == 1 && fz == 0)
                    continue; // same, for Z

                long cornerZ = iz + dz;

                double ddx = x - cornerX;
                double ddy = y - iy;
                double ddz = z - cornerZ;
                float distance = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                float decay = distance * decayPerOpacityUnit;

                int cr = ColorLightUtil.clamp(Math.round(baseR - decay));
                int cg = ColorLightUtil.clamp(Math.round(baseG - decay));
                int cb = ColorLightUtil.clamp(Math.round(baseB - decay));

                if (cr == 0 && cg == 0 && cb == 0)
                    continue; // negligible — not worth a flood-fill

                BlockPos cornerPos = new BlockPos((int) cornerX, iy, (int) cornerZ);
                int packed = ColorLightUtil.pack(cr, cg, cb);

                long key = cornerPos.asLong();
                sources.put(key, new SourceState(packed, resolvedMode));
                data.put(key, packed);
                queue.add(key);
                touched.add(cornerPos);
            }
        }

        propagateAdd(queue, resolvedMode);
        return touched;
    }

    private void addSourceRaw(BlockPos pos, int packed, ColorLightPropagationMode mode) {
        long key = pos.asLong();

        sources.put(key, new SourceState(packed, mode));
        data.put(key, packed);

        LongQueue queue = new LongQueue(initialQueueCapacity(mode));
        queue.add(key);
        propagateAdd(queue, mode);
    }

    private void propagateAdd(LongQueue queue, ColorLightPropagationMode mode) {
        if (queue.isEmpty())
            return;

        if (mode == ColorLightPropagationMode.SMOOTH) {
            propagateAddSmooth(queue);
        } else {
            propagateAddGrid(queue);
        }
    }

    /** Original behaviour: strictly axis-aligned, whole-unit decay per hop. */
    private void propagateAddGrid(LongQueue queue) {
        while (!queue.isEmpty()) {
            long key = queue.poll();
            BlockPos pos = BlockPos.of(key);
            int current = getRaw(key);

            int r = ColorLightUtil.r(current);
            int g = ColorLightUtil.g(current);
            int b = ColorLightUtil.b(current);

            if (r == 0 && g == 0 && b == 0)
                continue;

            for (Neighbor n : GRID_NEIGHBORS) {

                BlockPos neighborPos = pos.offset(n.dx(), n.dy(), n.dz());
                long neighborKey = neighborPos.asLong();

                int opacity = getOpacity(neighborPos);
                if (opacity >= VANILLA_MAX_OPACITY)
                    continue;

                int decay = Math.round((1 + opacity) * decayPerOpacityUnit);

                int nr = Math.max(0, r - decay);
                int ng = Math.max(0, g - decay);
                int nb = Math.max(0, b - decay);

                if (nr == 0 && ng == 0 && nb == 0)
                    continue;

                int neighborCurrent = getRaw(neighborKey);
                int cr = ColorLightUtil.r(neighborCurrent);
                int cg = ColorLightUtil.g(neighborCurrent);
                int cb = ColorLightUtil.b(neighborCurrent);

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;

                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    data.put(neighborKey, ColorLightUtil.pack(fr, fg, fb));
                    queue.add(neighborKey);
                }
            }
        }
    }

    /**
     * Alternate falloff: hops through all 26 neighbors (including diagonals),
     * so light isn't confined to the 3 cardinal coordinate axes, and decay is
     * scaled by each neighbor's real Euclidean distance. That distance scaling
     * is almost never a whole number (a face diagonal is √2 blocks away, a
     * corner diagonal √3), so the running value is tracked in eighth-unit
     * (0.125) fixed point for the duration of this flood-fill — precise enough
     * to avoid the directional rounding bias plain integer steps would build
     * up over a long diagonal run — and only rounded back to a whole unit when
     * it's written out to {@link #data}, so storage and everything reading it
     * (rendering, commands, save data) stays exactly as before.
     */
    private void propagateAddSmooth(LongQueue queue) {
        Map<Long, Long> eighths = new HashMap<>();

        while (!queue.isEmpty()) {
            long key = queue.poll();
            long currentEighths = eighths.computeIfAbsent(key, k -> toEighths(getRaw(k)));

            int r = eighthsR(currentEighths);
            int g = eighthsG(currentEighths);
            int b = eighthsB(currentEighths);

            if (r == 0 && g == 0 && b == 0)
                continue;

            BlockPos pos = BlockPos.of(key);

            for (Neighbor n : SMOOTH_NEIGHBORS) {

                BlockPos neighborPos = pos.offset(n.dx(), n.dy(), n.dz());
                long neighborKey = neighborPos.asLong();

                int opacity = getOpacity(neighborPos);
                if (opacity >= VANILLA_MAX_OPACITY)
                    continue;

                // At least one eighth-step so distance-scaled decay can never
                // round down to "no change", which would otherwise let light
                // leak forever along a direction with near-zero decay.
                int decay = Math.max(1, Math.round(n.distance() * (1 + opacity) * decayPerOpacityUnit * FIXED_POINT_SCALE));

                int nr = Math.max(0, r - decay);
                int ng = Math.max(0, g - decay);
                int nb = Math.max(0, b - decay);

                if (nr == 0 && ng == 0 && nb == 0)
                    continue;

                long neighborCurrentEighths = eighths.computeIfAbsent(neighborKey, k -> toEighths(getRaw(k)));
                int cr = eighthsR(neighborCurrentEighths);
                int cg = eighthsG(neighborCurrentEighths);
                int cb = eighthsB(neighborCurrentEighths);

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;

                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    eighths.put(neighborKey, packEighths(fr, fg, fb));
                    data.put(neighborKey, ColorLightUtil.pack(fromEighths(fr), fromEighths(fg), fromEighths(fb)));
                    queue.add(neighborKey);
                }
            }
        }
    }

    private static long packEighths(int r, int g, int b) {
        return (r & 0xFFFFL) | ((g & 0xFFFFL) << 16) | ((b & 0xFFFFL) << 32);
    }

    private static int eighthsR(long packed) {
        return (int) (packed & 0xFFFF);
    }

    private static int eighthsG(long packed) {
        return (int) ((packed >>> 16) & 0xFFFF);
    }

    private static int eighthsB(long packed) {
        return (int) ((packed >>> 32) & 0xFFFF);
    }

    private static long toEighths(int packedByteColor) {
        int r = ColorLightUtil.r(packedByteColor) * FIXED_POINT_SCALE;
        int g = ColorLightUtil.g(packedByteColor) * FIXED_POINT_SCALE;
        int b = ColorLightUtil.b(packedByteColor) * FIXED_POINT_SCALE;
        return packEighths(r, g, b);
    }

    private static int fromEighths(int eighthsValue) {
        return ColorLightUtil.clamp(Math.round(eighthsValue / (float) FIXED_POINT_SCALE));
    }

    public void removeSource(BlockPos pos) {
        long key = pos.asLong();
        SourceState removed = sources.remove(key);
        if (removed == null)
            return;

        int old = getRaw(key);
        data.put(key, ColorLightUtil.EMPTY);

        RelightPlan plan = darkenAndCollectSeeds(key, old);
        propagateAdd(plan.gridSeeds(), ColorLightPropagationMode.GRID);
        propagateAdd(plan.smoothSeeds(), ColorLightPropagationMode.SMOOTH);
    }

    /**
     * Invalidates the region downstream of a removed/changed color and sorts
     * the cells that need to radiate again into a GRID queue and a SMOOTH
     * queue, so each is re-lit with the same shape it originally had.
     * <p>
     * The search itself always walks the full 26-neighbor graph regardless of
     * which model(s) are actually in play nearby — that's strictly a superset
     * of the 6-neighbor GRID graph, so it's guaranteed to find every cell a
     * GRID-only invalidation would have found too. It only decides re-lighting
     * <em>shape</em> per seed: an encountered source re-lights with its own
     * stored model, and an ordinary surviving cell falls back to the engine's
     * default model, since a bare block position doesn't remember who lit it.
     */
    private RelightPlan darkenAndCollectSeeds(long startKey, int oldColorAtStart) {

        // Position keys and their packed pre-darken color travel in lockstep across two
        // primitive queues instead of one ArrayDeque<long[]>, avoiding a small array
        // allocation for every node visited while darkening.
        int capacity = initialQueueCapacity(ColorLightPropagationMode.SMOOTH);
        LongQueue darkenKeys = new LongQueue(capacity);
        IntQueue darkenColors = new IntQueue(capacity);
        LongQueue gridSeeds = new LongQueue(capacity);
        LongQueue smoothSeeds = new LongQueue(capacity);

        if (!ColorLightUtil.isEmpty(oldColorAtStart)) {
            darkenKeys.add(startKey);
            darkenColors.add(oldColorAtStart);
        }

        while (!darkenKeys.isEmpty()) {

            long curKey = darkenKeys.poll();
            int curColor = darkenColors.poll();
            int r = ColorLightUtil.r(curColor);
            int g = ColorLightUtil.g(curColor);
            int b = ColorLightUtil.b(curColor);

            BlockPos curPos = BlockPos.of(curKey);

            for (Neighbor n : SMOOTH_NEIGHBORS) {

                BlockPos neighborPos = curPos.offset(n.dx(), n.dy(), n.dz());
                long neighborKey = neighborPos.asLong();

                SourceState neighborSource = sources.get(neighborKey);
                if (neighborSource != null) {
                    addSeed(neighborSource.mode(), neighborKey, gridSeeds, smoothSeeds);
                    continue;
                }

                int neighborPacked = getRaw(neighborKey);
                int nr = ColorLightUtil.r(neighborPacked);
                int ng = ColorLightUtil.g(neighborPacked);
                int nb = ColorLightUtil.b(neighborPacked);

                if (nr == 0 && ng == 0 && nb == 0)
                    continue;

                boolean darkened = false;
                int fr = nr, fg = ng, fb = nb;

                if (nr != 0 && nr < r) { fr = 0; darkened = true; }
                if (ng != 0 && ng < g) { fg = 0; darkened = true; }
                if (nb != 0 && nb < b) { fb = 0; darkened = true; }

                if (darkened) {
                    data.put(neighborKey, ColorLightUtil.pack(fr, fg, fb));
                    darkenKeys.add(neighborKey);
                    darkenColors.add(ColorLightUtil.pack(nr, ng, nb));
                }

                if (nr >= r || ng >= g || nb >= b) {
                    addSeed(propagationMode, neighborKey, gridSeeds, smoothSeeds);
                }
            }
        }
        return new RelightPlan(gridSeeds, smoothSeeds);
    }

    private static void addSeed(ColorLightPropagationMode mode, long key, LongQueue gridSeeds, LongQueue smoothSeeds) {
        if (mode == ColorLightPropagationMode.SMOOTH) {
            smoothSeeds.add(key);
        } else {
            gridSeeds.add(key);
        }
    }

    public void onBlockChanged(BlockPos pos) {
        long key = pos.asLong();

        if (sources.containsKey(key))
            return;

        int old = getRaw(key);
        data.put(key, ColorLightUtil.EMPTY);

        RelightPlan plan = darkenAndCollectSeeds(key, old);

        addSeed(propagationMode, key, plan.gridSeeds(), plan.smoothSeeds());
        for (Neighbor n : SMOOTH_NEIGHBORS) {
            long neighborKey = pos.offset(n.dx(), n.dy(), n.dz()).asLong();
            SourceState neighborSource = sources.get(neighborKey);
            addSeed(neighborSource != null ? neighborSource.mode() : propagationMode, neighborKey, plan.gridSeeds(), plan.smoothSeeds());
        }

        propagateAdd(plan.gridSeeds(), ColorLightPropagationMode.GRID);
        propagateAdd(plan.smoothSeeds(), ColorLightPropagationMode.SMOOTH);
    }

    public float getDaylightFactor(BlockPos pos) {
        if (!(level instanceof Level realLevel))
            return 0f;

        float skyExposure = realLevel.getBrightness(LightLayer.SKY, pos) / 15f;

        float timeOfDayFactor = computeTimeOfDayFactor(realLevel);

        return ColorLightUtil.clamp01(skyExposure * timeOfDayFactor);
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

    protected int getOpacity(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return Math.max(0, Math.min(VANILLA_MAX_OPACITY, state.getLightDampening()));
    }

    public int sampleSmoothColor(BlockPos pos, Direction face, float vx, float vy, float vz) {

        BlockPos facePos = (face != null) ? pos.relative(face) : pos;

        if (face == null) {
            return ColorLightUtil.max(getColor(pos), getColor(facePos));
        }

        Direction.Axis axisA;
        Direction.Axis axisB;

        switch (face.getAxis()) {
            case X -> { axisA = Direction.Axis.Y; axisB = Direction.Axis.Z; }
            case Y -> { axisA = Direction.Axis.X; axisB = Direction.Axis.Z; }
            default -> { axisA = Direction.Axis.X; axisB = Direction.Axis.Y; }
        }

        float coordA = axisCoord(axisA, vx, vy, vz);
        float coordB = axisCoord(axisB, vx, vy, vz);

        int[] offsetsA = (coordA < 0.5f) ? new int[]{-1, 0} : new int[]{0, 1};
        int[] offsetsB = (coordB < 0.5f) ? new int[]{-1, 0} : new int[]{0, 1};

        int sumR = 0, sumG = 0, sumB = 0;
        int validSamples = 0;

        for (int oa : offsetsA) {
            for (int ob : offsetsB) {
                BlockPos samplePos = offsetAxis(offsetAxis(facePos, axisA, oa), axisB, ob);

                if (isOpaque(samplePos))
                    continue;

                int color = getColor(samplePos);
                sumR += ColorLightUtil.r(color);
                sumG += ColorLightUtil.g(color);
                sumB += ColorLightUtil.b(color);
                validSamples++;
            }
        }

        int avg = (validSamples > 0)
                ? ColorLightUtil.pack(Math.round(sumR / (float) validSamples), Math.round(sumG / (float) validSamples), Math.round(sumB / (float) validSamples))
                : ColorLightUtil.EMPTY;

        return ColorLightUtil.max(avg, getColor(pos));
    }

    public int sampleFlatColor(BlockPos pos, Direction face) {
        BlockPos facePos = (face != null) ? pos.relative(face) : pos;
        return ColorLightUtil.max(getColor(pos), getColor(facePos));
    }

    private static float axisCoord(Direction.Axis axis, float x, float y, float z) {
        return switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    private static BlockPos offsetAxis(BlockPos pos, Direction.Axis axis, int amount) {
        if (amount == 0) return pos;
        return switch (axis) {
            case X -> pos.offset(amount, 0, 0);
            case Y -> pos.offset(0, amount, 0);
            case Z -> pos.offset(0, 0, amount);
        };
    }
}