package me.mrhikmen.colorlight.client.core.light.propagation;

/**
 * Constants and lookup tables shared by both propagation models and by the engine's darkening pass: neighbour
 * offsets, per-opacity decay tables and the 1/8-unit fixed-point helpers. The tables are computed with the exact same float expressions the old
 * inline code used, so results are bit-for-bit identical - they just aren't recomputed for every
 * edge of every flood any more.
 */
public final class PropagationTables {

    /** Opacity at which a block stops light completely (vanilla's light dampening ceiling). */
    public static final int MAX_OPACITY = 15;

    /** Low 24 bits of a cell value: the colour. */
    public static final int RGB_MASK = 0x00FFFFFF;
    /** Top byte of a cell value: owner flags (see the engine), preserved by the propagators. */
    public static final int FLAG_MASK = 0xFF000000;

    /** SMOOTH tracks light in 1/8 units while spreading and rounds when storing. */
    public static final int FIXED_POINT_SCALE = 8;

    static final int OPACITY_LEVELS = 16;

    /** Same order as {@code Direction.values()}: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
    public static final int[] GRID_DX = {0, 0, 0, 0, -1, 1};
    public static final int[] GRID_DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] GRID_DZ = {0, 0, -1, 1, 0, 0};

    public static final int SMOOTH_COUNT = 26;
    public static final int[] SMOOTH_DX = new int[SMOOTH_COUNT];
    public static final int[] SMOOTH_DY = new int[SMOOTH_COUNT];
    public static final int[] SMOOTH_DZ = new int[SMOOTH_COUNT];
    public static final float[] SMOOTH_DIST = new float[SMOOTH_COUNT];

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
    public static int[] buildGridDecay(float decayPerOpacityUnit) {
        int[] table = new int[OPACITY_LEVELS];
        for (int opacity = 0; opacity < OPACITY_LEVELS; opacity++) {
            table[opacity] = Math.round((1 + opacity) * decayPerOpacityUnit);
        }
        return table;
    }

    /** decay (in 1/8 light units) of a hop to smooth-neighbour {@code n} into a block of the given opacity; never below 1. */
    public static int[][] buildSmoothDecay(float decayPerOpacityUnit, int fixedPointScale) {
        int[][] table = new int[SMOOTH_COUNT][OPACITY_LEVELS];
        for (int n = 0; n < SMOOTH_COUNT; n++) {
            for (int opacity = 0; opacity < OPACITY_LEVELS; opacity++) {
                table[n][opacity] = Math.max(1, Math.round(SMOOTH_DIST[n] * (1 + opacity) * decayPerOpacityUnit * fixedPointScale));
            }
        }
        return table;
    }

    public static long packEighths(int r, int g, int b) {
        return (r & 0xFFFFL) | ((g & 0xFFFFL) << 16) | ((b & 0xFFFFL) << 32);
    }

    public static int eighthsR(long packed) {
        return (int) (packed & 0xFFFF);
    }

    public static int eighthsG(long packed) {
        return (int) ((packed >>> 16) & 0xFFFF);
    }

    public static int eighthsB(long packed) {
        return (int) ((packed >>> 32) & 0xFFFF);
    }

    private PropagationTables() {
    }
}
