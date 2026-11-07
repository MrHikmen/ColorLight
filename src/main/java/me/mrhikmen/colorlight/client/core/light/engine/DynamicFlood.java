package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.cell.LightPalette;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator;
import me.mrhikmen.colorlight.client.core.light.util.LongQueue;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelAccessor;

import java.util.Arrays;


/**
 * Computes the {@link DynamicFootprint} of one moving light source.
 * <p>
 * It spreads the light with a table-driven propagation method from the resource packs (setting
 * {@code dynamic_propagation}, by default the circular {@code colorlight:smooth}), but over a
 * private, empty field: it reads block opacity from the level and never touches the engine's static light data
 * or lock. That means it can run on a background thread while the game thread keeps editing blocks and static
 * lights. All up-to-27 sub-block seed corners (a 3x3x3 neighbourhood, interpolated on X, Y and Z alike) are
 * flooded in <b>one</b> pass.
 * <p>
 * Instances are single-threaded: give every worker thread its own.
 */
final class DynamicFlood {

    private static final int POSITION_SUBDIVISIONS = 8;

    private final LevelAccessor level;
    private final float decayPerOpacityUnit;
    private final TablePropagator smooth;
    private final LightPalette palette;
    /** Working-value units per light unit of the chosen method (the method's {@code scale}). */
    private final int scale;
    private final int maxWorking;

    private final LongQueue queue = new LongQueue(1 << 10);

    /**
     * A light that starts from nothing: no pre-existing values, and nothing is stored while spreading - the
     * result is read back from the propagator's working values, which keep the full 1/8-unit precision.
     * (Same class as the engine's static field, just without storage - see {@link WorldLightField}.)
     */
    private final WorldLightField emptyField;

    DynamicFlood(LevelAccessor level, LightPalette palette, float decayPerOpacityUnit, Identifier methodId, Identifier fallbackId) {
        this.level = level;
        this.palette = palette;
        this.decayPerOpacityUnit = decayPerOpacityUnit;
        TablePropagator table = PropagationMethodRegistry.tableFor(methodId, fallbackId, decayPerOpacityUnit);
        if (table == null)
            throw new IllegalStateException("no table-driven propagation method is registered");
        this.smooth = table;
        this.scale = table.scale();
        this.maxWorking = 255 * scale;
        this.emptyField = new WorldLightField(level, null, null);
    }

    /** @return the footprint, or {@code null} if the light is too dim to reach any cell. */
    DynamicFootprint compute(double x, double y, double z, int r, int g, int b, int strength) {
        int rgb = ColorLightUtil.pack(r, g, b);
        int peak = LightPalette.peakOf(rgb);
        if (peak <= 0)
            return null;
        int id = palette.idFor(rgb);

        emptyField.beginPass();
        smooth.beginPass();
        queue.clear();

        // (not named "scale": that would hide the method's fixed-point scale field used below, seeding the light
        // 1/scale too weak - for smooth, scale 8, it then barely spread and showed as a small cube)
        float strengthFraction = ColorLightUtil.clamp01(strength / 15f);
        float brightness = peak * strengthFraction;

        long ex = Math.round(x * POSITION_SUBDIVISIONS);
        long ey = Math.round(y * POSITION_SUBDIVISIONS);
        long ez = Math.round(z * POSITION_SUBDIVISIONS);
        // Nearest whole-block corner to the light, not floor(x)/floor(y)/floor(z): floor always put the light
        // against the "low" edge of the corner grid (it only ever sits between ix and ix+1), so the extra corner
        // added below for the 3x3x3 neighbourhood ended up almost entirely on one side of it. Rounding to the
        // nearest corner instead means the light sits in the middle cell of the 3x3x3 block, with one full layer
        // of corners on every side of it, however it happens to be positioned within its own block.
        long cx = Math.floorDiv(ex + POSITION_SUBDIVISIONS / 2, POSITION_SUBDIVISIONS);
        long cy = Math.floorDiv(ey + POSITION_SUBDIVISIONS / 2, POSITION_SUBDIVISIONS);
        long cz = Math.floorDiv(ez + POSITION_SUBDIVISIONS / 2, POSITION_SUBDIVISIONS);
        int fx = (int) (ex - cx * POSITION_SUBDIVISIONS);
        int fy = (int) (ey - cy * POSITION_SUBDIVISIONS);
        int fz = (int) (ez - cz * POSITION_SUBDIVISIONS);

        // 3x3x3 neighbourhood of corners centred on the light instead of the old 2x2x2 (just the lower and upper
        // corner on each axis, floor-anchored): seeding one more corner on every side gives the near-field falloff
        // an extra directly-computed, exact-distance layer before the coarser hop-based propagator takes
        // over further out, which is what actually removes the popping/stepping otherwise visible while a light
        // crosses a sub-block boundary.
        for (int dx = -1; dx <= 1; dx++) {
            if (dx != 0 && fx == 0)
                continue;
            long cornerX = cx + dx;

            for (int dy = -1; dy <= 1; dy++) {
                if (dy != 0 && fy == 0)
                    continue;
                long cornerY = cy + dy;

                for (int dz = -1; dz <= 1; dz++) {
                    if (dz != 0 && fz == 0)
                        continue;
                    long cornerZ = cz + dz;

                    double ddx = x - cornerX;
                    double ddy = y - cornerY;
                    double ddz = z - cornerZ;
                    float decay = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) * decayPerOpacityUnit;

                    long start = clampWorking(Math.round((brightness - decay) * scale));
                    if (start <= 0)
                        continue;

                    long key = PosKey.pack((int) cornerX, (int) cornerY, (int) cornerZ);
                    smooth.seedWorking(key, start);
                    queue.add(key);
                }
            }
        }

        DynamicFootprint result = null;
        if (!queue.isEmpty()) {
            smooth.run(id, queue, emptyField);
            result = buildFootprint(id);
        }

        emptyField.endPass();
        smooth.trim();
        return result;
    }

    /** Opacity at which a block stops a beam completely. */
    private static final int BEAM_BLOCKING_OPACITY = 15;
    /** Ray-march step towards the source when checking what stands in the way, in blocks. */
    private static final double BEAM_RAY_STEP = 0.5;

    /**
     * Fills a cone along {@code beam} (see {@link LightBeam}), with Illuminated's shape and brightness.
     * <p>
     * Every cell of the box around the source is tested against the cone's distance field; a lit cell is then checked
     * with a short ray back to the source over a dense, lazily filled opacity cache (no hashing, no path search), so
     * walls cut the beam and thin blocks dim it. Cheap enough to redo whenever the holder turns.
     *
     * @return the footprint, or {@code null} if the beam reaches no cell
     */
    DynamicFootprint computeBeam(double x, double y, double z, int r, int g, int b, int strength, LightBeam beam) {
        int rgb = ColorLightUtil.pack(r, g, b);
        int peak = LightPalette.peakOf(rgb);
        if (peak <= 0)
            return null;
        int id = palette.idFor(rgb);

        // brightness (0..255 level) per light unit (0..15) of the beam
        float gain = peak * ColorLightUtil.clamp01(strength / 15f) / 15f;
        if (gain <= 0)
            return null;

        double dirX = beam.dirX(), dirY = beam.dirY(), dirZ = beam.dirZ();
        double radius = beam.radius(), depth = beam.depth(), offset = beam.offset();
        double half = depth * 0.5;

        int reach = (int) Math.ceil(Math.hypot(depth - offset, radius)) + 1;
        int size = 2 * reach + 1;
        int ox = (int) Math.floor(x) - reach, oy = (int) Math.floor(y) - reach, oz = (int) Math.floor(z) - reach;

        byte[] opacity = new byte[size * size * size];
        Arrays.fill(opacity, (byte) -1);
        int[] levelAt = new int[size * size * size];
        long[] keys = new long[256];
        int count = 0;

        emptyField.beginPass();

        for (int ix = 0; ix < size; ix++) {
            double vx = ox + ix + 0.5 - x;
            for (int iy = 0; iy < size; iy++) {
                double vy = oy + iy + 0.5 - y;
                for (int iz = 0; iz < size; iz++) {
                    double vz = oz + iz + 0.5 - z;

                    double t = vx * dirX + vy * dirY + vz * dirZ;      // distance along the view
                    double ya = half - offset - t;                      // Illuminated's "entity space" y
                    double radial = Math.sqrt(Math.max(0.0, vx * vx + vy * vy + vz * vz - t * t));
                    double sdf = Math.min(radius * (0.5 - ya / depth) - radial, half - Math.abs(ya));
                    if (sdf <= 0.0)
                        continue;

                    double edge = Math.min(1.0, sdf * sdf * sdf * (sdf * (sdf * 6.0 - 15.0) + 10.0));
                    if (sdf < 1.0 && edge <= 0.0)
                        continue;
                    double light = Math.min(15.0, 15.0 * depth / Math.pow(Math.max(t, 0.5), 1.5)) * edge;

                    int index = (ix * size + iy) * size + iz;
                    if (cachedOpacity(opacity, index, ox + ix, oy + iy, oz + iz) >= BEAM_BLOCKING_OPACITY)
                        continue; // a solid block: the light sits in the air next to it

                    double blocked = beamOpacityToSource(opacity, size, ox, oy, oz, x, y, z, vx, vy, vz);
                    if (blocked >= BEAM_BLOCKING_OPACITY)
                        continue;
                    light *= 1.0 - blocked / BEAM_BLOCKING_OPACITY;

                    int level = Math.min(ColorLightUtil.MAX, (int) Math.round(light * gain));
                    if (level <= 0)
                        continue;

                    levelAt[index] = level;
                    if (count == keys.length)
                        keys = Arrays.copyOf(keys, count * 2);
                    keys[count++] = PosKey.pack(ox + ix, oy + iy, oz + iz);
                }
            }
        }

        emptyField.endPass();
        if (count == 0)
            return null;

        long[] sorted = Arrays.copyOf(keys, count);
        Arrays.sort(sorted);
        int[] levels = new int[count];
        for (int i = 0; i < count; i++) {
            long key = sorted[i];
            levels[i] = levelAt[((PosKey.x(key) - ox) * size + (PosKey.y(key) - oy)) * size + (PosKey.z(key) - oz)];
        }
        return new DynamicFootprint(sorted, levels, id);
    }

    private int cachedOpacity(byte[] cache, int index, int x, int y, int z) {
        int value = cache[index];
        if (value < 0) {
            value = emptyField.opacityAt(x, y, z);
            cache[index] = (byte) value;
        }
        return value;
    }

    /** Opacity the beam passes between the source and a cell centre (the cells in between, not either end). */
    private double beamOpacityToSource(byte[] cache, int size, int ox, int oy, int oz,
                                       double x, double y, double z, double vx, double vy, double vz) {
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        int steps = (int) Math.ceil(length / BEAM_RAY_STEP);
        double sum = 0;
        int lastIndex = -1;
        for (int k = 1; k < steps; k++) {
            double f = (double) k / steps;
            int cx = (int) Math.floor(x + vx * f), cy = (int) Math.floor(y + vy * f), cz = (int) Math.floor(z + vz * f);
            int index = (((cx - ox) * size + (cy - oy)) * size) + (cz - oz);
            if (index == lastIndex)
                continue;
            lastIndex = index;
            int op = cachedOpacity(cache, index, cx, cy, cz);
            if (op >= BEAM_BLOCKING_OPACITY)
                return BEAM_BLOCKING_OPACITY;
            sum += op;
        }
        return sum;
    }

    private long clampWorking(long v) {
        return v < 0 ? 0 : Math.min(v, maxWorking);
    }

    private DynamicFootprint buildFootprint(int id) {
        long[] keys = new long[smooth.cellCount()];
        int n = smooth.collectKeys(keys);

        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (smooth.toLevel(smooth.workingAt(keys[i])) != 0)
                keys[kept++] = keys[i];
        }
        if (kept == 0)
            return null;

        long[] sorted = Arrays.copyOf(keys, kept);
        Arrays.sort(sorted);

        int[] levels = new int[kept];
        for (int i = 0; i < kept; i++) {
            levels[i] = smooth.toLevel(smooth.workingAt(sorted[i]));
        }
        return new DynamicFootprint(sorted, levels, id);
    }
}
