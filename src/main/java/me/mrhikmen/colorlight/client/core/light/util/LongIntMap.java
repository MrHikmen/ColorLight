package me.mrhikmen.colorlight.client.core.light.util;

import java.util.Arrays;
import java.util.function.LongConsumer;

/**
 * Small primitive open-addressing hash map (long -> int) with deletion. Replaces
 * {@code ConcurrentHashMap<Long, ...>} on the engine's mutation path, where every lookup used to
 * box a {@link Long}. NOT thread-safe: callers guard it with the engine lock.
 */
public final class LongIntMap {

    private static final long PHI = 0x9E3779B97F4A7C15L;
    private static final int SHRINK_ABOVE = 1 << 12;
    private static final int SHRINK_TO = 1 << 8;

    private long[] keys;
    private int[] vals;
    private boolean[] used;
    private int size;
    private int mask;
    private int shift;

    public LongIntMap(int initialCapacity) {
        allocate(Math.max(16, Integer.highestOneBit(Math.max(2, initialCapacity) - 1) << 1));
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        vals = new int[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        shift = 64 - Integer.numberOfTrailingZeros(capacity);
        size = 0;
    }

    private int slot(long key) {
        return (int) ((key * PHI) >>> shift);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean containsKey(long key) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key)
                return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    public int get(long key, int def) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key)
                return vals[i];
            i = (i + 1) & mask;
        }
        return def;
    }

    public void put(long key, int value) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) {
                vals[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        vals[i] = value;
        if (++size * 2 > keys.length)
            grow();
    }

    public boolean remove(long key) {
        int i = slot(key);
        while (used[i]) {
            if (keys[i] == key) {
                removeAt(i);
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }

    /** Backward-shift deletion: keeps probe chains intact without tombstones. */
    private void removeAt(int i) {
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            if (!used[j])
                break;
            int ideal = slot(keys[j]);
            boolean inRange = (i <= j) ? (i < ideal && ideal <= j) : (i < ideal || ideal <= j);
            if (inRange)
                continue;
            keys[i] = keys[j];
            vals[i] = vals[j];
            i = j;
        }
        used[i] = false;
        size--;
    }

    /** Empties the map. A table that grew large during a burst shrinks back, so later passes over it stay cheap. */
    public void clear() {
        if (keys.length > SHRINK_ABOVE) {
            allocate(SHRINK_TO);
            return;
        }
        if (size == 0)
            return;
        Arrays.fill(used, false);
        size = 0;
    }

    public void forEachKey(LongConsumer consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (used[i])
                consumer.accept(keys[i]);
        }
    }

    public long[] keysToArray() {
        long[] out = new long[size];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (used[i])
                out[n++] = keys[i];
        }
        return out;
    }

    /** Snapshot of the values, index-aligned with {@link #keysToArray()} called at the same moment. */
    public int[] valuesToArray() {
        int[] out = new int[size];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (used[i])
                out[n++] = vals[i];
        }
        return out;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldVals = vals;
        boolean[] oldUsed = used;
        allocate(oldKeys.length << 1);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i])
                put(oldKeys[i], oldVals[i]);
        }
    }
}
