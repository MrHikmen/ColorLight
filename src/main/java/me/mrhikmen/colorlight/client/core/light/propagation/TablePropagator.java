package me.mrhikmen.colorlight.client.core.light.propagation;

import me.mrhikmen.colorlight.client.core.light.util.LongQueue;
import me.mrhikmen.colorlight.client.core.light.util.PassMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.MAX_OPACITY;

/**
 * The one flood-fill kernel behind every data-driven propagation method, spreading ONE number: the brightness of the
 * light of one colour. The colour is not part of the flood at all (it is only a palette id the field is told about),
 * so it can never drift or be rounded on the way.
 * <p>
 * <b>What the resource pack decides</b> (all of it comes from a {@code .json} file, see {@code PropagationSpec}):
 * <ul>
 *     <li>which neighbours light hops to ({@code dx, dy, dz} offsets, each component -1..1);</li>
 *     <li>how much brightness a hop into a block of a given opacity costs (a table precomputed from the pack's
 *     {@code loss} formula, so no formula is evaluated inside the flood loop);</li>
 *     <li>the fixed-point precision the running value is kept in ({@code scale}, 1 = whole units).</li>
 * </ul>
 * <b>What stays here:</b> the loop itself. Cells are expanded brightest first, so each cell is expanded once, when no
 * brighter value can still reach it; the result is the per-cell maximum over the seeds taken one by one.
 * <p>
 * Holds scratch memory: not thread-safe, use one instance per thread.
 */
public final class TablePropagator implements LightPropagator {

    private final int[] dx, dy, dz;
    /** {@code decay[n][opacity]}: cost in 1/scale units of a hop to neighbour n into a block of that opacity (always &gt;= 1). */
    private final int[][] decay;
    private final int scale;
    private final int maxOpacity;

    /** Brightness (1/scale units) the current pass has reached in the cells it touched. */
    private final PassMap working = new PassMap(1 << 12);
    private final LongQueue queue = new LongQueue(1 << 10);

    /**
     * @param preserveHue no longer used: the colour is never part of the flood, so it always stays as it is
     */
    public TablePropagator(int[] dx, int[] dy, int[] dz, int[][] decay, int scale, boolean preserveHue, int maxOpacity) {
        if (dx.length != dy.length || dx.length != dz.length || dx.length != decay.length)
            throw new IllegalArgumentException("neighbour arrays must have the same length");
        if (scale < 1 || scale > 64)
            throw new IllegalArgumentException("scale must be within 1..64");
        this.dx = dx.clone();
        this.dy = dy.clone();
        this.dz = dz.clone();
        this.decay = decay;
        this.scale = scale;
        this.maxOpacity = Math.max(1, Math.min(maxOpacity, MAX_OPACITY));
    }

    /** Offset of neighbour n of a hop. */
    public int offsetX(int n) { return dx[n]; }
    public int offsetY(int n) { return dy[n]; }
    public int offsetZ(int n) { return dz[n]; }

    /** Cost (1/scale units) of a hop to neighbour n into a block of the given opacity. */
    public int hopCost(int n, int opacity) { return decay[n][opacity]; }

    /** How many neighbours a hop reaches (6 = faces only, i.e. the diamond shape; more = rounder). */
    public int neighbourCount() {
        return dx.length;
    }

    /** Fixed-point precision (working values are in 1/scale light units). */
    public int scale() {
        return scale;
    }

    // ---- working values: for callers that seed / read them directly (dynamic lights) ----

    public void beginPass() {
        working.clear();
    }

    /** Gives a cell a starting brightness (in 1/scale units) before {@link #run}; keeps the higher of two seeds. */
    public void seedWorking(long key, long value) {
        if (value > working.get(key, 0L))
            working.put(key, value);
    }

    /** Brightness (1/scale units) the current pass has at a cell, or 0 if it never touched it. */
    public long workingAt(long key) {
        return working.get(key, 0L);
    }

    public int cellCount() {
        return working.size();
    }

    public int collectKeys(long[] out) {
        return working.collectKeys(out);
    }

    public void trim() {
        working.trim();
    }

    /** A working value in whole light units, rounded and clamped to 0..255. */
    public int toLevel(long workingValue) {
        if (workingValue <= 0)
            return 0;
        long level = (workingValue + (scale >> 1)) / scale;
        return level > 255 ? 255 : (int) level;
    }

    // ---- spreading ----

    @Override
    public void spread(int id, long[] seedKeys, int[] seedLevels, int seedCount, LightField field) {
        field.beginPass();
        beginPass();
        queue.clear();
        for (int i = 0; i < seedCount; i++) {
            long start = (long) seedLevels[i] * scale;
            if (start <= 0)
                continue;
            seedWorking(seedKeys[i], start);
            queue.add(seedKeys[i]);
        }
        run(id, queue, field);
        trim();
        field.endPass();
    }

    /** Keys waiting to be expanded at each brightness (1/scale units); index = brightness. */
    private LongQueue[] buckets = new LongQueue[0];

    private LongQueue bucket(int b) {
        if (b >= buckets.length)
            buckets = java.util.Arrays.copyOf(buckets, Math.max(b + 1, buckets.length * 2));
        LongQueue q = buckets[b];
        if (q == null) {
            q = new LongQueue(16);
            buckets[b] = q;
        }
        return q;
    }

    /**
     * The flood proper. Cells are expanded in order of brightness, brightest first. Every hop costs at least 1, so
     * brightness only falls along a path: a cell expanded at brightness b can never be reached brighter later, and it
     * is expanded once. Same per-cell maximum as the label-correcting loop, without re-queueing on every improvement.
     * The queue is consumed.
     */
    public void run(int id, LongQueue queue, LightField field) {
        final int count = dx.length;
        int top = 0;
        while (!queue.isEmpty()) {
            long key = queue.poll();
            long v = working.get(key, 0L);
            if (v <= 0)
                continue;
            int b = (int) Math.min(v, 1 << 20);
            bucket(b).add(key);
            if (b > top)
                top = b;
        }

        for (int b = top; b > 0; b--) {
            LongQueue q = buckets[b];
            if (q == null)
                continue;
            while (!q.isEmpty()) {
                long key = q.poll();
                long cur = working.get(key, 0L);
                if (cur != b)
                    continue; // improved after it was queued: it is expanded from its higher bucket
                int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);
                field.raise(x, y, z, id, toLevel(cur));

                for (int n = 0; n < count; n++) {
                    int nx = x + dx[n], ny = y + dy[n], nz = z + dz[n];

                    int opacity = field.opacityAt(nx, ny, nz);
                    if (opacity >= maxOpacity)
                        continue;

                    long next = cur - decay[n][opacity];
                    if (next <= 0)
                        continue;

                    long nKey = PosKey.pack(nx, ny, nz);
                    long previous = working.get(nKey, 0L);
                    if (next <= previous)
                        continue;

                    working.put(nKey, next);
                    bucket((int) Math.min(next, 1 << 20)).add(nKey); // next < cur: expanded later in this sweep
                }
            }
        }
    }
}
