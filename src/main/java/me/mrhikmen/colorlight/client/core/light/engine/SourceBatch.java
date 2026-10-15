package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;

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

    /**
     * Reorders the entries so the ones closest to ({@code px}, {@code py}, {@code pz}) come first; the engine
     * floods them in this order, so light appears around the player before it appears further away.
     */
    public void sortByDistance(int px, int py, int pz) {
        if (size < 2)
            return;

        long[] order = new long[size]; // (squared distance << 20) | index: sorts by distance, ties by index
        for (int i = 0; i < size; i++) {
            long dx = PosKey.x(keys[i]) - px, dy = PosKey.y(keys[i]) - py, dz = PosKey.z(keys[i]) - pz;
            long dist = Math.min(dx * dx + dy * dy + dz * dz, (1L << 40) - 1);
            order[i] = (dist << 20) | i;
        }
        Arrays.sort(order, 0, size);

        long[] newKeys = new long[keys.length];
        int[] newColors = new int[colors.length];
        Identifier[] newMethods = new Identifier[methods.length];
        for (int i = 0; i < size; i++) {
            int from = (int) (order[i] & 0xFFFFF);
            newKeys[i] = keys[from];
            newColors[i] = colors[from];
            newMethods[i] = methods[from];
        }
        keys = newKeys;
        colors = newColors;
        methods = newMethods;
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
