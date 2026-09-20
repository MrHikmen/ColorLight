package me.mrhikmen.colorlight.core.light.engine;

/**
 * Packs block coordinates into a single {@code long} using exactly the same layout as
 * {@code BlockPos.asLong()} (26 bits X, 26 bits Z, 12 bits Y), but without ever allocating a
 * {@code BlockPos}. The hot loops of the engine work on raw ints/longs only.
 */
public final class PosKey {

    public static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 38);
    }

    public static int y(long key) {
        return (int) (key << 52 >> 52);
    }

    public static int z(long key) {
        return (int) (key << 26 >> 38);
    }

    private PosKey() {
    }
}
