package me.mrhikmen.colorlight.core.light.engine;

import java.util.Arrays;

/**
 * Immutable result of one dynamic light flood: the cells (sorted, for binary search) a moving light
 * currently touches and the colour it gives each of them. It lives in its own layer and is
 * never written into the static light field, which is what makes moving a light cheap - the old
 * position is forgotten by simply dropping the previous footprint, with no "darkening" pass through
 * whatever static light happens to overlap it.
 */
public final class DynamicFootprint {

    final long[] keys;
    final int[] colors;
    final int minX, minY, minZ, maxX, maxY, maxZ;

    /** Set when a block changed inside this footprint, meaning it must be recomputed even if the light didn't move. */
    volatile boolean stale;

    DynamicFootprint(long[] sortedKeys, int[] colors) {
        this.keys = sortedKeys;
        this.colors = colors;

        int loX = Integer.MAX_VALUE, loY = Integer.MAX_VALUE, loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE, hiY = Integer.MIN_VALUE, hiZ = Integer.MIN_VALUE;
        for (long key : sortedKeys) {
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);
            if (x < loX) loX = x;
            if (y < loY) loY = y;
            if (z < loZ) loZ = z;
            if (x > hiX) hiX = x;
            if (y > hiY) hiY = y;
            if (z > hiZ) hiZ = z;
        }
        this.minX = loX; this.minY = loY; this.minZ = loZ;
        this.maxX = hiX; this.maxY = hiY; this.maxZ = hiZ;
    }

    public int size() {
        return keys.length;
    }

    boolean boundsContain(int x, int y, int z, int margin) {
        return x >= minX - margin && x <= maxX + margin
                && y >= minY - margin && y <= maxY + margin
                && z >= minZ - margin && z <= maxZ + margin;
    }

    /** Colour at the given cell, or 0 if this footprint doesn't cover it. */
    int colorAt(int x, int y, int z, long key) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ)
            return 0;
        int i = Arrays.binarySearch(keys, key);
        return i >= 0 ? colors[i] : 0;
    }
}
