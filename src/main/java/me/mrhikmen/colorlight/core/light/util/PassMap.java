package me.mrhikmen.colorlight.core.light.util;

import java.util.Arrays;

/**
 * Per-flood-fill scratch map (long -> long) that is "cleared" in O(1) by bumping a generation
 * counter. Used to memoize block opacity and the sub-unit (1/8) light precision for the duration
 * of a single propagation pass, so neither has to be re-fetched/boxed for every one of a cell's
 * 6 or 26 neighbours. NOT thread-safe.
 */
public final class PassMap {

    private static final long PHI = 0x9E3779B97F4A7C15L;
    private static final int TRIM_ABOVE = 1 << 17;
    private static final int TRIM_TO = 1 << 12;

    private long[] keys;
    private long[] vals;
    private int[] stamps;
    private int gen = 1;
    private int size;
    private int mask;
    private int shift;

    public PassMap(int initialCapacity) {
        allocate(Math.max(16, Integer.highestOneBit(Math.max(2, initialCapacity) - 1) << 1));
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        vals = new long[capacity];
        stamps = new int[capacity];
        mask = capacity - 1;
        shift = 64 - Integer.numberOfTrailingZeros(capacity);
        gen = 1;
        size = 0;
    }

    private int slot(long key) {
        return (int) ((key * PHI) >>> shift);
    }

    public int size() {
        return size;
    }

    public long get(long key, long def) {
        int i = slot(key);
        while (stamps[i] == gen) {
            if (keys[i] == key)
                return vals[i];
            i = (i + 1) & mask;
        }
        return def;
    }

    public boolean contains(long key) {
        int i = slot(key);
        while (stamps[i] == gen) {
            if (keys[i] == key)
                return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    public void put(long key, long value) {
        int i = slot(key);
        while (stamps[i] == gen) {
            if (keys[i] == key) {
                vals[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        stamps[i] = gen;
        keys[i] = key;
        vals[i] = value;
        if (++size * 2 > keys.length)
            grow();
    }

    /** Copies every live key into {@code out} (which must hold at least {@link #size()} entries). */
    public int collectKeys(long[] out) {
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (stamps[i] == gen)
                out[n++] = keys[i];
        }
        return n;
    }

    public void clear() {
        size = 0;
        if (++gen == Integer.MAX_VALUE) {
            Arrays.fill(stamps, 0);
            gen = 1;
        }
    }

    /** Drops an oversized backing array after a pathological flood so it doesn't stay pinned. */
    public void trim() {
        if (keys.length > TRIM_ABOVE)
            allocate(TRIM_TO);
    }

    private void grow() {
        long[] oldKeys = keys;
        long[] oldVals = vals;
        int[] oldStamps = stamps;
        int oldGen = gen;
        allocate(oldKeys.length << 1);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldStamps[i] == oldGen)
                put(oldKeys[i], oldVals[i]);
        }
    }
}
