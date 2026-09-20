package me.mrhikmen.colorlight.core.light.engine;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the footprints of all currently tracked moving lights and the merged (per-channel max)
 * colour field they produce. Sampling reads {@code max(staticField, dynamicField)}, so the two never
 * interfere: a moving light crossing a torch's glow costs exactly what it costs in an empty cave.
 * <p>
 * Mutations ({@link #replace}, {@link #remove}, {@link #clear}) must be serialised by the caller
 * (the engine lock). Reads of {@link #storage()} are lock-free.
 */
final class DynamicLightLayer {

    /** Called for each cell whose merged colour changed. */
    interface CellListener {
        void cellChanged(int x, int y, int z);
    }

    private static final DynamicFootprint[] NONE = new DynamicFootprint[0];

    private final LightStorage storage = new LightStorage();
    private final CellListener listener;

    private final ConcurrentHashMap<Integer, DynamicFootprint> footprints = new ConcurrentHashMap<>();
    private volatile DynamicFootprint[] active = NONE;

    DynamicLightLayer(CellListener listener) {
        this.listener = listener;
    }

    LightStorage storage() {
        return storage;
    }

    boolean isActive() {
        return active.length != 0;
    }

    boolean isStale(int id) {
        DynamicFootprint fp = footprints.get(id);
        return fp != null && fp.stale;
    }

    boolean has(int id) {
        return footprints.containsKey(id);
    }

    /** Flags every footprint whose (slightly padded) bounds contain the changed block so it gets recomputed. */
    void markStaleAround(int x, int y, int z) {
        for (DynamicFootprint fp : active) {
            if (fp.boundsContain(x, y, z, 1))
                fp.stale = true;
        }
    }

    /** Swaps in a new footprint for {@code id} (or removes it when {@code fp} is null) and updates only the cells that changed. */
    void replace(int id, DynamicFootprint fp) {
        DynamicFootprint old = (fp != null) ? footprints.put(id, fp) : footprints.remove(id);
        if (old == null && fp == null)
            return;

        active = footprints.isEmpty() ? NONE : footprints.values().toArray(NONE);

        if (fp != null) {
            boolean single = active.length == 1;
            for (int i = 0; i < fp.keys.length; i++) {
                long key = fp.keys[i];
                int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);
                write(x, y, z, single ? fp.colors[i] : merged(x, y, z, key));
            }
        }

        if (old != null) {
            for (long key : old.keys) {
                if (fp != null && java.util.Arrays.binarySearch(fp.keys, key) >= 0)
                    continue;
                int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);
                write(x, y, z, merged(x, y, z, key));
            }
        }
    }

    void remove(int id) {
        replace(id, null);
    }

    void clear() {
        footprints.clear();
        active = NONE;
        storage.clear();
    }

    private int merged(int x, int y, int z, long key) {
        int r = 0, g = 0, b = 0;
        for (DynamicFootprint f : active) {
            int c = f.colorAt(x, y, z, key);
            if (c == 0)
                continue;
            int cr = c & 0xFF, cg = (c >> 8) & 0xFF, cb = (c >> 16) & 0xFF;
            if (cr > r) r = cr;
            if (cg > g) g = cg;
            if (cb > b) b = cb;
        }
        return r | (g << 8) | (b << 16);
    }

    private void write(int x, int y, int z, int value) {
        if (storage.get(x, y, z) == value)
            return;
        storage.put(x, y, z, value);
        listener.cellChanged(x, y, z);
    }
}
