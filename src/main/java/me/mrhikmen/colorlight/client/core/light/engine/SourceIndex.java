package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * The static light sources of the world by the 16x16x16 section they are in, so "which sources are near this cell" is
 * a walk over a few sections instead of over every source. Only touched while holding the engine's lock.
 */
final class SourceIndex {

    private static final class Bucket {
        long[] keys = new long[4];
        int size;
    }

    private final Map<Long, Bucket> buckets = new HashMap<>();

    private static long sectionOf(long key) {
        return PosKey.pack(PosKey.x(key) >> 4, PosKey.y(key) >> 4, PosKey.z(key) >> 4);
    }

    void add(long key) {
        Bucket b = buckets.computeIfAbsent(sectionOf(key), k -> new Bucket());
        for (int i = 0; i < b.size; i++) {
            if (b.keys[i] == key)
                return;
        }
        if (b.size == b.keys.length)
            b.keys = Arrays.copyOf(b.keys, b.size << 1);
        b.keys[b.size++] = key;
    }

    void remove(long key) {
        long section = sectionOf(key);
        Bucket b = buckets.get(section);
        if (b == null)
            return;
        for (int i = 0; i < b.size; i++) {
            if (b.keys[i] == key) {
                b.keys[i] = b.keys[--b.size];
                if (b.size == 0)
                    buckets.remove(section);
                return;
            }
        }
    }

    void clear() {
        buckets.clear();
    }

    /** Calls {@code consumer} with the key of every source within {@code radius} blocks (per axis) of the cell. */
    void forEachNear(int x, int y, int z, int radius, LongConsumer consumer) {
        int sx0 = (x - radius) >> 4, sx1 = (x + radius) >> 4;
        int sy0 = (y - radius) >> 4, sy1 = (y + radius) >> 4;
        int sz0 = (z - radius) >> 4, sz1 = (z + radius) >> 4;
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sy = sy0; sy <= sy1; sy++) {
                for (int sz = sz0; sz <= sz1; sz++) {
                    Bucket b = buckets.get(PosKey.pack(sx, sy, sz));
                    if (b == null)
                        continue;
                    for (int i = 0; i < b.size; i++) {
                        long key = b.keys[i];
                        if (Math.abs(PosKey.x(key) - x) <= radius && Math.abs(PosKey.y(key) - y) <= radius && Math.abs(PosKey.z(key) - z) <= radius)
                            consumer.accept(key);
                    }
                }
            }
        }
    }
}
