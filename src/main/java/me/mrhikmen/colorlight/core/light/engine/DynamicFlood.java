package me.mrhikmen.colorlight.core.light.engine;

import me.mrhikmen.colorlight.core.light.color.ColorLightUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

import java.util.Arrays;

import static me.mrhikmen.colorlight.core.light.engine.PropagationTables.*;

/**
 * Computes the {@link DynamicFootprint} of one moving light source.
 * <p>
 * It is a self-contained smooth (26-neighbour, 1/8-unit precision) flood-fill over a private scratch
 * map - it reads block opacity from the level but never touches the engine's static light data or
 * lock. That means it can run on a background thread while the game thread keeps editing blocks
 * and static lights. All up-to-4 sub-block seed corners are flooded in <b>one</b> pass (the old code
 * ran a separate full flood for each).
 * <p>
 * Instances are single-threaded: give every worker thread its own.
 */
final class DynamicFlood {

    private static final int FIXED_POINT_SCALE = 8;
    private static final int POSITION_SUBDIVISIONS = 8;
    private static final int MAX_EIGHTHS = 255 * FIXED_POINT_SCALE;

    private final LevelAccessor level;
    private final float decayPerOpacityUnit;
    private final int[][] smoothDecay;

    private final PassMap opacityMemo = new PassMap(1 << 11);
    private final PassMap field = new PassMap(1 << 11);
    private final LongQueue queue = new LongQueue(1 << 10);
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    DynamicFlood(LevelAccessor level, float decayPerOpacityUnit) {
        this.level = level;
        this.decayPerOpacityUnit = decayPerOpacityUnit;
        this.smoothDecay = buildSmoothDecay(decayPerOpacityUnit, FIXED_POINT_SCALE);
    }

    private int opacityAt(int x, int y, int z) {
        long key = PosKey.pack(x, y, z);
        long cached = opacityMemo.get(key, -1L);
        if (cached >= 0)
            return (int) cached;

        scratchPos.set(x, y, z);
        int opacity = level.getBlockState(scratchPos).getLightDampening();
        opacity = Math.max(0, Math.min(ColorLightEngine.VANILLA_MAX_OPACITY, opacity));
        opacityMemo.put(key, opacity);
        return opacity;
    }

    /** @return the footprint, or {@code null} if the light is too dim to reach any cell. */
    DynamicFootprint compute(double x, double y, double z, int r, int g, int b, int strength) {
        opacityMemo.clear();
        field.clear();
        queue.clear();

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

        for (int dx = 0; dx <= 1; dx++) {
            if (dx == 1 && fx == 0)
                continue;
            long cornerX = ix + dx;

            for (int dz = 0; dz <= 1; dz++) {
                if (dz == 1 && fz == 0)
                    continue;
                long cornerZ = iz + dz;

                double ddx = x - cornerX;
                double ddy = y - iy;
                double ddz = z - cornerZ;
                float decay = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) * decayPerOpacityUnit;

                int er = clampEighths(Math.round((baseR - decay) * FIXED_POINT_SCALE));
                int eg = clampEighths(Math.round((baseG - decay) * FIXED_POINT_SCALE));
                int eb = clampEighths(Math.round((baseB - decay) * FIXED_POINT_SCALE));
                if (er == 0 && eg == 0 && eb == 0)
                    continue;

                long key = PosKey.pack((int) cornerX, iy, (int) cornerZ);
                long existing = field.get(key, 0L);
                int mr = Math.max(er, eighthsR(existing));
                int mg = Math.max(eg, eighthsG(existing));
                int mb = Math.max(eb, eighthsB(existing));
                field.put(key, packEighths(mr, mg, mb));
                queue.add(key);
            }
        }

        if (queue.isEmpty())
            return null;

        flood();
        DynamicFootprint result = buildFootprint();

        opacityMemo.trim();
        field.trim();
        return result;
    }

    private static int clampEighths(int v) {
        return v < 0 ? 0 : Math.min(v, MAX_EIGHTHS);
    }

    private void flood() {
        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);

            long cur = field.get(key, 0L);
            int r = eighthsR(cur), g = eighthsG(cur), b = eighthsB(cur);
            if (r == 0 && g == 0 && b == 0)
                continue;

            for (int n = 0; n < SMOOTH_COUNT; n++) {
                int nx = x + SMOOTH_DX[n], ny = y + SMOOTH_DY[n], nz = z + SMOOTH_DZ[n];

                int opacity = opacityAt(nx, ny, nz);
                if (opacity >= ColorLightEngine.VANILLA_MAX_OPACITY)
                    continue;

                int decay = smoothDecay[n][opacity];
                int nr = r - decay; if (nr < 0) nr = 0;
                int ng = g - decay; if (ng < 0) ng = 0;
                int nb = b - decay; if (nb < 0) nb = 0;
                if (nr == 0 && ng == 0 && nb == 0)
                    continue;

                long nKey = PosKey.pack(nx, ny, nz);
                long nCur = field.get(nKey, 0L);
                int cr = eighthsR(nCur), cg = eighthsG(nCur), cb = eighthsB(nCur);

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;
                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    field.put(nKey, packEighths(fr, fg, fb));
                    queue.add(nKey);
                }
            }
        }
    }

    private DynamicFootprint buildFootprint() {
        long[] keys = new long[field.size()];
        int n = field.collectKeys(keys);

        int kept = 0;
        for (int i = 0; i < n; i++) {
            long v = field.get(keys[i], 0L);
            if (toRgb(v) != 0)
                keys[kept++] = keys[i];
        }
        if (kept == 0)
            return null;

        long[] sorted = Arrays.copyOf(keys, kept);
        Arrays.sort(sorted);

        int[] colors = new int[kept];
        for (int i = 0; i < kept; i++) {
            colors[i] = toRgb(field.get(sorted[i], 0L));
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
