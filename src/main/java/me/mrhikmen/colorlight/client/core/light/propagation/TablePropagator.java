package me.mrhikmen.colorlight.client.core.light.propagation;

import me.mrhikmen.colorlight.client.core.light.util.IntQueue;
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

    /** Smallest cost of any hop into a block the flood can pass (opacity below maxOpacity). Bounds how far light reaches. */
    private final int minCost;

    /**
     * Dense scratch for {@link #spread}: one int per cell of the reachable box. Layout (low to high bits):
     * value 11 bits | opacity 4 bits | opacity known 1 bit | generation 16 bits. One memory access per neighbour.
     */
    public static final int DENSE_MAX_CELLS = 1 << 20;
    private static final int VAL_MASK = 0x7FF, OPAC_SHIFT = 11, OPAC_KNOWN = 1 << 15, GEN_SHIFT = 16, GEN_MAX = 0xFFFF;
    private int[] dense = new int[0];
    private int dGen = 0;
    private final LongQueue queue = new LongQueue(1 << 10);

    /** {@code decayFlat[n * 16 + opacity]}: the {@link #decay} table in one array (one load instead of two). */
    private final int[] decayFlat;
    /** Cheapest hop to neighbour n into any block the flood can pass: a hop that cannot beat 0 even at this cost is skipped unread. */
    private final int[] minDecay;
    /** {@code levelOf[v]}: {@link #toLevel} of every working value the dense path can hold (0..VAL_MASK). */
    private final int[] levelOf = new int[VAL_MASK + 1];
    /** Dense path: index offset of neighbour n inside the scratch box (depends on the box, set per flood). */
    private final int[] stepIdx;
    /** Dense path: cells waiting at each brightness, as indexes into {@link #dense}. */
    private IntQueue[] intBuckets = new IntQueue[0];
    /** Dense path: the cells the flood expanded (index, final brightness), written to the field in one batch at the end. */
    private int[] outIdx = new int[1 << 12];
    private int[] outLevel = new int[1 << 12];
    private long[] outKeys = new long[1 << 12];

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
        int mc = Integer.MAX_VALUE;
        for (int[] row : decay)
            for (int o = 0; o < Math.min(this.maxOpacity, row.length); o++)
                mc = Math.min(mc, row[o]);
        this.minCost = Math.max(1, mc);

        this.decayFlat = new int[dx.length * 16];
        this.minDecay = new int[dx.length];
        this.stepIdx = new int[dx.length];
        for (int n = 0; n < dx.length; n++) {
            int cheapest = Integer.MAX_VALUE;
            for (int o = 0; o < 16; o++) {
                int c = o < decay[n].length ? decay[n][o] : Integer.MAX_VALUE / 4;
                decayFlat[n * 16 + o] = c;
                if (o < this.maxOpacity)
                    cheapest = Math.min(cheapest, c);
            }
            minDecay[n] = Math.max(1, cheapest);
        }
        for (int v = 0; v <= VAL_MASK; v++)
            levelOf[v] = toLevel(v);
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
        // Bounding box of everything the flood can reach: a cell at Chebyshev distance k from a seed has value
        // <= start - k * minCost, so only k <= (maxStart - 1) / minCost can be lit at all.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long maxStart = 0;
        for (int i = 0; i < seedCount; i++) {
            long start = (long) seedLevels[i] * scale;
            if (start <= 0)
                continue;
            long k = seedKeys[i];
            int x = PosKey.x(k), y = PosKey.y(k), z = PosKey.z(k);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            maxStart = Math.max(maxStart, start);
        }
        if (maxStart <= 0) {
            field.beginPass();
            field.endPass();
            return;
        }
        if (maxStart > VAL_MASK) {
            spreadHashed(id, seedKeys, seedLevels, seedCount, field);
            return;
        }
        long reach = (maxStart - 1) / minCost;
        long sx = (long) maxX - minX + 1 + 2 * reach;
        long sy = (long) maxY - minY + 1 + 2 * reach;
        long sz = (long) maxZ - minZ + 1 + 2 * reach;
        if (sx * sy * sz > DENSE_MAX_CELLS) {
            spreadHashed(id, seedKeys, seedLevels, seedCount, field);
            return;
        }
        // One cell of padding on every side: a hop that is not skipped by the cost bound below always lands inside
        // the reachable box, the padding only makes a wrong bound harmless instead of an out-of-bounds read.
        final int x0 = (int) (minX - reach) - 1, y0 = (int) (minY - reach) - 1, z0 = (int) (minZ - reach) - 1;
        final int nx0 = (int) sx + 2, ny0 = (int) sy + 2, nz0 = (int) sz + 2;
        final int plane = ny0 * nz0;
        final int vol = nx0 * plane;
        if (dense.length < vol) {
            dense = new int[vol];
            dGen = 0;
        }

        field.beginPass();
        if (++dGen > GEN_MAX) {
            java.util.Arrays.fill(dense, 0);
            dGen = 1;
        }
        final int gen = dGen;
        final int[] cells = dense;
        final int genTag = gen << GEN_SHIFT;
        final int OPAC_BITS = 0xF << OPAC_SHIFT;
        final int count = dx.length;
        final int[] step = stepIdx;
        for (int n = 0; n < count; n++)
            step[n] = (dx[n] * ny0 + dy[n]) * nz0 + dz[n];
        final int[] minCostOf = minDecay;
        final int[] flat = decayFlat;
        final int[] lvl = levelOf;

        int top = 0;
        for (int i = 0; i < seedCount; i++) {
            long start = (long) seedLevels[i] * scale;
            if (start <= 0)
                continue;
            long key = seedKeys[i];
            int s = (int) start;
            int idx = ((PosKey.x(key) - x0) * ny0 + (PosKey.y(key) - y0)) * nz0 + (PosKey.z(key) - z0);
            int c = cells[idx];
            if ((c >>> GEN_SHIFT) == gen) {
                if (s <= (c & VAL_MASK))
                    continue;                               // an equal or stronger seed is already queued here
                cells[idx] = (c & ~VAL_MASK) | s;      // same pass: keep the opacity already cached
            } else {
                cells[idx] = genTag | s;                    // first touch this pass
            }
            intBucket(s).add(idx);
            if (s > top)
                top = s;
        }

        int outN = 0;
        for (int b = top; b > 0; b--) {
            IntQueue q = b < intBuckets.length ? intBuckets[b] : null;
            if (q == null)
                continue;
            while (!q.isEmpty()) {
                int idx = q.poll();
                int c = cells[idx];
                int cur = c & VAL_MASK;
                if (cur != b)
                    continue; // improved after it was queued: expanded from its higher bucket

                if (outN == outIdx.length) {
                    outIdx = java.util.Arrays.copyOf(outIdx, outN * 2);
                    outLevel = java.util.Arrays.copyOf(outLevel, outN * 2);
                }
                outIdx[outN] = idx;
                outLevel[outN++] = lvl[cur];

                // coordinates of the cell inside the box, only needed when a neighbour's opacity has to be read
                int ax = -1, ay = 0, az = 0;

                for (int n = 0; n < count; n++) {
                    if (cur - minCostOf[n] <= 0)
                        continue; // even the cheapest hop leaves nothing: no need to look at the neighbour at all
                    int nIdx = idx + step[n];
                    int nc = cells[nIdx];

                    int opacity;
                    int previous;
                    if ((nc >>> GEN_SHIFT) == gen) {
                        previous = nc & VAL_MASK;
                        if ((nc & OPAC_KNOWN) != 0) {
                            opacity = (nc & OPAC_BITS) >>> OPAC_SHIFT;
                        } else {
                            if (ax < 0) { ax = idx / plane; int r = idx - ax * plane; ay = r / nz0; az = r - ay * nz0; }
                            opacity = field.opacityUncached(x0 + ax + dx[n], y0 + ay + dy[n], z0 + az + dz[n]) & 0xF;
                            cells[nIdx] = (nc & ~OPAC_BITS) | (opacity << OPAC_SHIFT) | OPAC_KNOWN;
                        }
                    } else {
                        previous = 0;
                        if (ax < 0) { ax = idx / plane; int r = idx - ax * plane; ay = r / nz0; az = r - ay * nz0; }
                        opacity = field.opacityUncached(x0 + ax + dx[n], y0 + ay + dy[n], z0 + az + dz[n]) & 0xF;
                        cells[nIdx] = genTag | (opacity << OPAC_SHIFT) | OPAC_KNOWN;
                    }
                    if (opacity >= maxOpacity)
                        continue;

                    int next = cur - flat[(n << 4) | opacity];
                    if (next <= previous)
                        continue; // also covers next <= 0, since previous >= 0

                    cells[nIdx] = genTag | (opacity << OPAC_SHIFT) | OPAC_KNOWN | next;
                    intBucket(next).add(nIdx);
                }
            }
        }

        if (outN > 0) {
            if (outKeys.length < outN)
                outKeys = new long[Math.max(outN, outKeys.length * 2)];
            final long[] keys = outKeys;
            final int[] oi = outIdx;
            for (int i = 0; i < outN; i++) {
                int idx = oi[i];
                int ax = idx / plane;
                int r = idx - ax * plane;
                int ay = r / nz0;
                keys[i] = PosKey.pack(x0 + ax, y0 + ay, z0 + (r - ay * nz0));
            }
            field.raiseBatch(id, keys, outLevel, 0, outN);
        }
        field.endPass();
    }

    private IntQueue intBucket(int b) {
        if (b >= intBuckets.length)
            intBuckets = java.util.Arrays.copyOf(intBuckets, Math.max(b + 1, intBuckets.length * 2));
        IntQueue q = intBuckets[b];
        if (q == null) {
            q = new IntQueue(16);
            intBuckets[b] = q;
        }
        return q;
    }

    /** The original hashed path, used only when the reachable box would be too big for dense scratch. */
    private void spreadHashed(int id, long[] seedKeys, int[] seedLevels, int seedCount, LightField field) {
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
