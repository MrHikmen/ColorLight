package me.mrhikmen.colorlight.core.light.engine;

import me.mrhikmen.colorlight.core.light.util.PosKey;

import me.mrhikmen.colorlight.core.light.color.ColorLightUtil;

import net.minecraft.resources.Identifier;

import java.util.Arrays;

/**
 * Primitive, allocation-light list of light sources found while scanning a chunk. It replaces a
 * {@code List<record(BlockPos, r, g, b, strength, propagationMethod)>}, i.e. one {@code BlockPos} plus
 * one record per source.
 */
public final class SourceBatch {

    private long[] keys = new long[16];
    private int[] colors = new int[16];
    /** Per-entry propagation method id ({@code null} = engine default), parallel to {@link #colors}. */
    private Identifier[] methods = new Identifier[16];
    private int size;

    /**
     * Adds a source using the engine's default propagation method; {@code strength} scales the colour
     * exactly like {@code ColorLightEngine#addSource}.
     */
    public void add(int x, int y, int z, int r, int g, int b, int strength) {
        add(x, y, z, r, g, b, strength, null);
    }

    /**
     * Adds a source that spreads with {@code propagationMethod} specifically (e.g. the id a block's
     * {@code BlockSettings#getPropagationId()} resolved to); {@code null} keeps the engine default.
     */
    public void add(int x, int y, int z, int r, int g, int b, int strength, Identifier propagationMethod) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size << 1);
            colors = Arrays.copyOf(colors, size << 1);
            methods = Arrays.copyOf(methods, size << 1);
        }
        float scale = ColorLightUtil.clamp01(strength / 15f);
        keys[size] = PosKey.pack(x, y, z);
        colors[size] = ColorLightUtil.pack(Math.round(r * scale), Math.round(g * scale), Math.round(b * scale));
        methods[size] = propagationMethod;
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

    Identifier method(int i) {
        return methods[i];
    }
}
