package me.mrhikmen.colorlight.client.core.light.propagation;

/**
 * Constants shared by the propagation kernels and by the engine's darkening pass, plus the packing helpers for
 * fixed-point working values (three 16-bit channels in one long). The neighbour offsets and decay tables are not
 * here any more: they come from the resource pack's Lua propagation scripts.
 *
 * <p>The engine's darkening pass and re-seeding always walk the full 26-neighbourhood, {@link #NEIGHBOR_COUNT}
 * offsets below - which is why a propagation script may only hop to cells within that neighbourhood.
 */
public final class PropagationTables {

    /** Opacity at which a block stops light completely (vanilla's light dampening ceiling). */
    public static final int MAX_OPACITY = 15;

    /** Low 24 bits of a cell value: the colour. */
    public static final int RGB_MASK = 0x00FFFFFF;
    /** Top byte of a cell value: owner flags (see the engine), preserved by the propagators. */
    public static final int FLAG_MASK = 0xFF000000;

    /** Number of opacity levels a decay table covers (0..15). */
    public static final int OPACITY_LEVELS = 16;

    public static final int NEIGHBOR_COUNT = 26;
    public static final int[] NEIGHBOR_DX = new int[NEIGHBOR_COUNT];
    public static final int[] NEIGHBOR_DY = new int[NEIGHBOR_COUNT];
    public static final int[] NEIGHBOR_DZ = new int[NEIGHBOR_COUNT];

    static {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0)
                        continue;
                    NEIGHBOR_DX[n] = dx;
                    NEIGHBOR_DY[n] = dy;
                    NEIGHBOR_DZ[n] = dz;
                    n++;
                }
            }
        }
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
