package me.mrhikmen.colorlight.core.light.engine;

import me.mrhikmen.colorlight.core.light.color.ColorLightUtil;

import java.util.Arrays;

/**
 * Primitive, allocation-light list of light sources found while scanning a chunk. It replaces a
 * {@code List<record(BlockPos, r, g, b, strength)>}, i.e. one {@code BlockPos} plus one record per source.
 */
public final class SourceBatch {

    private long[] keys = new long[16];
    private int[] colors = new int[16];
    private int size;

    /** Adds a source; {@code strength} scales the colour exactly like {@code ColorLightEngine#addSource}. */
    public void add(int x, int y, int z, int r, int g, int b, int strength) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size << 1);
            colors = Arrays.copyOf(colors, size << 1);
        }
        float scale = ColorLightUtil.clamp01(strength / 15f);
        keys[size] = PosKey.pack(x, y, z);
        colors[size] = ColorLightUtil.pack(Math.round(r * scale), Math.round(g * scale), Math.round(b * scale));
        size++;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    long key(int i) {
        return keys[i];
    }

    int color(int i) {
        return colors[i];
    }
}
