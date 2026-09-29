package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.propagation.SmoothPropagator;
import me.mrhikmen.colorlight.client.core.light.util.LongQueue;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import net.minecraft.world.level.LevelAccessor;

import java.util.Arrays;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.*;

/**
 * Computes the {@link DynamicFootprint} of one moving light source.
 * <p>
 * It spreads the light with the same {@link SmoothPropagator} (circle) that SMOOTH block light uses, but over a
 * private, empty field: it reads block opacity from the level and never touches the engine's static light data
 * or lock. That means it can run on a background thread while the game thread keeps editing blocks and static
 * lights. All up-to-27 sub-block seed corners (a 3x3x3 neighbourhood, interpolated on X, Y and Z alike) are
 * flooded in <b>one</b> pass.
 * <p>
 * Instances are single-threaded: give every worker thread its own.
 */
final class DynamicFlood {

    private static final int POSITION_SUBDIVISIONS = 8;
    private static final int MAX_EIGHTHS = 255 * FIXED_POINT_SCALE;

    private final LevelAccessor level;
    private final float decayPerOpacityUnit;
    private final SmoothPropagator smooth;

    private final LongQueue queue = new LongQueue(1 << 10);

    /**
     * A light that starts from nothing: no pre-existing values, and nothing is stored while spreading - the
     * result is read back from the propagator's working values, which keep the full 1/8-unit precision.
     * (Same class as the engine's static field, just without storage - see {@link WorldLightField}.)
     */
    private final WorldLightField emptyField;

    DynamicFlood(LevelAccessor level, float decayPerOpacityUnit) {
        this.level = level;
        this.decayPerOpacityUnit = decayPerOpacityUnit;
        this.smooth = new SmoothPropagator(decayPerOpacityUnit);
        this.emptyField = new WorldLightField(level, null, null);
    }

    /** @return the footprint, or {@code null} if the light is too dim to reach any cell. */
    DynamicFootprint compute(double x, double y, double z, int r, int g, int b, int strength) {
        emptyField.beginPass();
        smooth.beginPass();
        queue.clear();

        float scale = ColorLightUtil.clamp01(strength / 15f);
        float baseR = r * scale;
        float baseG = g * scale;
        float baseB = b * scale;

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
        // an extra directly-computed, exact-distance layer before the coarser hop-based SmoothPropagator takes
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

                    int er = clampEighths(Math.round((baseR - decay) * FIXED_POINT_SCALE));
                    int eg = clampEighths(Math.round((baseG - decay) * FIXED_POINT_SCALE));
                    int eb = clampEighths(Math.round((baseB - decay) * FIXED_POINT_SCALE));
                    if (er == 0 && eg == 0 && eb == 0)
                        continue;

                    long key = PosKey.pack((int) cornerX, (int) cornerY, (int) cornerZ);
                    long existing = smooth.eighthsAt(key);
                    int mr = Math.max(er, eighthsR(existing));
                    int mg = Math.max(eg, eighthsG(existing));
                    int mb = Math.max(eb, eighthsB(existing));
                    smooth.seed(key, packEighths(mr, mg, mb));
                    queue.add(key);
                }
            }
        }

        if (queue.isEmpty())
            return null;

        smooth.run(queue, emptyField);
        DynamicFootprint result = buildFootprint();

        emptyField.endPass();
        smooth.trim();
        return result;
    }

    private static int clampEighths(int v) {
        return v < 0 ? 0 : Math.min(v, MAX_EIGHTHS);
    }

    private DynamicFootprint buildFootprint() {
        long[] keys = new long[smooth.cellCount()];
        int n = smooth.collectKeys(keys);

        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (toRgb(smooth.eighthsAt(keys[i])) != 0)
                keys[kept++] = keys[i];
        }
        if (kept == 0)
            return null;

        long[] sorted = Arrays.copyOf(keys, kept);
        Arrays.sort(sorted);

        int[] colors = new int[kept];
        for (int i = 0; i < kept; i++) {
            colors[i] = toRgb(smooth.eighthsAt(sorted[i]));
        }
        return new DynamicFootprint(sorted, colors);
    }

    private static int toRgb(long eighths) {
        int r = Math.round(eighthsR(eighths) / (float) FIXED_POINT_SCALE);
        int g = Math.round(eighthsG(eighths) / (float) FIXED_POINT_SCALE);
        int b = Math.round(eighthsB(eighths) / (float) FIXED_POINT_SCALE);
        return ColorLightUtil.pack(r, g, b);
    }
}