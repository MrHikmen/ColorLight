package me.mrhikmen.colorlight.core.light.engine;

/**
 * Constant neighbour offsets and per-opacity decay lookup tables shared by the static engine and
 * the dynamic-light flood. The tables are computed with the exact same float expressions the old
 * inline code used, so results are bit-for-bit identical - they just aren't recomputed for every
 * edge of every flood any more.
 */
final class PropagationTables {

    static final int OPACITY_LEVELS = 16;

    /** Same order as {@code Direction.values()}: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
    static final int[] GRID_DX = {0, 0, 0, 0, -1, 1};
    static final int[] GRID_DY = {-1, 1, 0, 0, 0, 0};
    static final int[] GRID_DZ = {0, 0, -1, 1, 0, 0};

    static final int SMOOTH_COUNT = 26;
    static final int[] SMOOTH_DX = new int[SMOOTH_COUNT];
    static final int[] SMOOTH_DY = new int[SMOOTH_COUNT];
    static final int[] SMOOTH_DZ = new int[SMOOTH_COUNT];
    static final float[] SMOOTH_DIST = new float[SMOOTH_COUNT];

    static {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0)
                        continue;
                    SMOOTH_DX[n] = dx;
                    SMOOTH_DY[n] = dy;
                    SMOOTH_DZ[n] = dz;
                    SMOOTH_DIST[n] = (float) Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                    n++;
                }
            }
        }
    }

    /** decay (in whole light units) of one axis-aligned hop into a block of the given opacity. */
    static int[] buildGridDecay(float decayPerOpacityUnit) {
        int[] table = new int[OPACITY_LEVELS];
        for (int opacity = 0; opacity < OPACITY_LEVELS; opacity++) {
            table[opacity] = Math.round((1 + opacity) * decayPerOpacityUnit);
        }
        return table;
    }

    /** decay (in 1/8 light units) of a hop to smooth-neighbour {@code n} into a block of the given opacity; never below 1. */
    static int[][] buildSmoothDecay(float decayPerOpacityUnit, int fixedPointScale) {
        int[][] table = new int[SMOOTH_COUNT][OPACITY_LEVELS];
        for (int n = 0; n < SMOOTH_COUNT; n++) {
            for (int opacity = 0; opacity < OPACITY_LEVELS; opacity++) {
                table[n][opacity] = Math.max(1, Math.round(SMOOTH_DIST[n] * (1 + opacity) * decayPerOpacityUnit * fixedPointScale));
            }
        }
        return table;
    }

    static long packEighths(int r, int g, int b) {
        return (r & 0xFFFFL) | ((g & 0xFFFFL) << 16) | ((b & 0xFFFFL) << 32);
    }

    static int eighthsR(long packed) {
        return (int) (packed & 0xFFFF);
    }

    static int eighthsG(long packed) {
        return (int) ((packed >>> 16) & 0xFFFF);
    }

    static int eighthsB(long packed) {
        return (int) ((packed >>> 32) & 0xFFFF);
    }

    private PropagationTables() {
    }
}
