package me.mrhikmen.colorlight.client.core.light.propagation;

import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.util.LongQueue;
import me.mrhikmen.colorlight.client.core.light.util.PassMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.*;

/**
 * The one flood-fill kernel behind every data-driven propagation method.
 * <p>
 * <b>What the resource pack decides</b> (all of it comes from a {@code .json} file, see {@code PropagationSpec}):
 * <ul>
 *     <li>which neighbours light hops to ({@code dx, dy, dz} offsets, each component -1..1);</li>
 *     <li>how much brightness a hop into a block of a given opacity costs (a table precomputed from the pack's
 *     {@code loss} formula - so no per-hop parsing or scripting ever runs inside the flood loop, which is what keeps this fast);</li>
 *     <li>the fixed-point precision the running value is kept in ({@code scale}, 1 = whole units);</li>
 *     <li>whether the colour's ratio (its hue) is preserved while it fades.</li>
 * </ul>
 * <b>What stays here:</b> the loop itself. It is the same algorithm the old hard-coded grid and smooth propagators used
 * (with {@code scale == 1} it works directly on the field like the old grid one; with {@code scale > 1} it keeps
 * the running values in a scratch map like the old smooth one), so a pack that describes them gets identical light.
 * <p>
 * Holds scratch memory: not thread-safe, use one instance per thread.
 */
public final class TablePropagator implements LightPropagator {

    private final int[] dx, dy, dz;
    /** {@code decay[n][opacity]}: cost in 1/scale units of a hop to neighbour n into a block of that opacity (always &gt;= 1). */
    private final int[][] decay;
    private final int scale;
    private final boolean preserveHue;
    private final int maxOpacity;

    /** Working values (1/scale units) of the cells touched during the current pass. */
    private final PassMap working = new PassMap(1 << 12);

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
        this.preserveHue = preserveHue;
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

    /** Gives a cell a starting value (packed with {@link PropagationTables#packEighths}, in 1/scale units) before {@link #run}. */
    public void seed(long key, long packed) {
        working.put(key, packed);
    }

    /** Working value of a cell in the current pass (1/scale units, packed), or 0 if the pass never touched it. */
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

    // ---- spreading ----

    @Override
    public void propagate(LongQueue queue, LightField field) {
        field.beginPass();
        if (scale == 1) {
            runDirect(queue, field);
        } else {
            beginPass();
            run(queue, field);
            trim();
        }
        field.endPass();
    }

    /** Whole-unit flood working straight on the field (no scratch map). Used when {@code scale == 1}. */
    private void runDirect(LongQueue queue, LightField field) {
        final int count = dx.length;
        // per-channel fading (Colorful Lighting by erykczy, MIT) instead of keeping the hue while fading
        final boolean additive = ColorLightUtil.additiveMix();
        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);

            int cur = field.get(x, y, z);
            int r = cur & 0xFF, g = (cur >> 8) & 0xFF, b = (cur >> 16) & 0xFF;
            if ((r | g | b) == 0)
                continue;

            int peak = Math.max(r, Math.max(g, b));

            for (int n = 0; n < count; n++) {
                int nx = x + dx[n], ny = y + dy[n], nz = z + dz[n];

                int opacity = field.opacityAt(nx, ny, nz);
                if (opacity >= maxOpacity)
                    continue;

                int loss = decay[n][opacity];
                int nr, ng, nb;
                if (preserveHue && !additive) {
                    // the strongest channel anchors the reach; the others are scaled against it so the ratio holds
                    int newPeak = peak - loss;
                    if (newPeak <= 0)
                        continue;
                    nr = (r * newPeak) / peak;
                    ng = (g * newPeak) / peak;
                    nb = (b * newPeak) / peak;
                } else {
                    nr = Math.max(0, r - loss);
                    ng = Math.max(0, g - loss);
                    nb = Math.max(0, b - loss);
                    int newPeak = peak - loss;
                    if (newPeak <= 0)
                        continue;
                    if (newPeak * 3 <= 255 * scale * 2) {
                        nr = (r * newPeak) / peak;
                        ng = (g * newPeak) / peak;
                        nb = (b * newPeak) / peak;
                    }
                }
                if ((nr | ng | nb) == 0)
                    continue;

                int nCell = field.get(nx, ny, nz);
                int cr = nCell & 0xFF, cg = (nCell >> 8) & 0xFF, cb = (nCell >> 16) & 0xFF;

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;
                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    field.set(nx, ny, nz, (nCell & FLAG_MASK) | fr | (fg << 8) | (fb << 16));
                    queue.add(PosKey.pack(nx, ny, nz));
                }
            }
        }
    }

    /** The flood-fill proper with sub-unit precision, without clearing the working values first (so they can be seeded). */
    public void run(LongQueue queue, LightField field) {
        final int count = dx.length;
        final boolean additive = ColorLightUtil.additiveMix();
        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);

            long cur = working.get(key, -1L);
            if (cur < 0) {
                cur = toWorking(field.get(x, y, z));
                working.put(key, cur);
            }

            int r = eighthsR(cur), g = eighthsG(cur), b = eighthsB(cur);
            if ((r | g | b) == 0)
                continue;

            int peak = Math.max(r, Math.max(g, b));

            for (int n = 0; n < count; n++) {
                int nx = x + dx[n], ny = y + dy[n], nz = z + dz[n];

                int opacity = field.opacityAt(nx, ny, nz);
                if (opacity >= maxOpacity)
                    continue;

                int loss = decay[n][opacity];
                int nr, ng, nb;
                if (preserveHue && !additive) {
                    int newPeak = peak - loss;
                    if (newPeak <= 0)
                        continue;
                    nr = (r * newPeak) / peak;
                    ng = (g * newPeak) / peak;
                    nb = (b * newPeak) / peak;
                } else {
                    nr = Math.max(0, r - loss);
                    ng = Math.max(0, g - loss);
                    nb = Math.max(0, b - loss);
                    int newPeak = peak - loss;
                    if (newPeak <= 0)
                        continue;
                    if (newPeak * 3 <= 255 * scale * 2) {
                        nr = (r * newPeak) / peak;
                        ng = (g * newPeak) / peak;
                        nb = (b * newPeak) / peak;
                    }
                }
                if ((nr | ng | nb) == 0)
                    continue;

                long nKey = PosKey.pack(nx, ny, nz);
                int nCell = field.get(nx, ny, nz);

                long nWork = working.get(nKey, -1L);
                if (nWork < 0)
                    nWork = toWorking(nCell);

                int cr = eighthsR(nWork), cg = eighthsG(nWork), cb = eighthsB(nWork);

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;
                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    working.put(nKey, packEighths(fr, fg, fb));

                    int rounded = ColorLightUtil.pack(fromWorking(fr), fromWorking(fg), fromWorking(fb));
                    if (rounded != (nCell & RGB_MASK)) {
                        field.set(nx, ny, nz, (nCell & FLAG_MASK) | rounded);
                    }
                    queue.add(nKey);
                }
            }
        }
    }

    @Override
    public void propagateIsolated(LongQueue queue, LightField field) {
        field.beginPass();
        beginPass();
        runIsolated(queue, field);
        trim();
        field.endPass();
    }

    /**
     * The flood-fill of {@link #run}, except that the running values of this pass start from nothing in every cell the pass
     * reaches (only the seeds start from what is stored): light stored by other passes is neither carried on nor
     * compared against, but the stored cell is raised to the per-channel maximum of both. Each pass is then exactly what
     * its light alone would be, and the cell shows the maximum of all of them.
     */
    private void runIsolated(LongQueue queue, LightField field) {
        final int count = dx.length;
        final boolean additive = ColorLightUtil.additiveMix();

        // the seeds start from what they hold (a source: its own colour); every other cell starts from nothing, and so does
        // a source that this pass did not start from (light of another colour that crosses it is not mixed with its own on the way on)
        int seeds = 0;
        long[] seedKeys = new long[Math.max(16, queue.size())];
        while (!queue.isEmpty()) {
            if (seeds == seedKeys.length)
                seedKeys = java.util.Arrays.copyOf(seedKeys, seeds << 1);
            seedKeys[seeds++] = queue.poll();
        }
        for (int i = 0; i < seeds; i++) {
            long key = seedKeys[i];
            working.put(key, toWorking(field.seedColor(PosKey.x(key), PosKey.y(key), PosKey.z(key))));
            queue.add(key);
        }
        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);

            long cur = working.get(key, -1L);
            if (cur < 0) {
                cur = toWorking(field.get(x, y, z));
                working.put(key, cur);
            }

            int r = eighthsR(cur), g = eighthsG(cur), b = eighthsB(cur);
            if ((r | g | b) == 0)
                continue;

            int peak = Math.max(r, Math.max(g, b));

            for (int n = 0; n < count; n++) {
                int nx = x + dx[n], ny = y + dy[n], nz = z + dz[n];

                int opacity = field.opacityAt(nx, ny, nz);
                if (opacity >= maxOpacity)
                    continue;

                int loss = decay[n][opacity];
                int nr, ng, nb;
                if (preserveHue && !additive) {
                    int newPeak = peak - loss;
                    if (newPeak <= 0)
                        continue;
                    nr = (r * newPeak) / peak;
                    ng = (g * newPeak) / peak;
                    nb = (b * newPeak) / peak;
                } else {
                    nr = Math.max(0, r - loss);
                    ng = Math.max(0, g - loss);
                    nb = Math.max(0, b - loss);
                    int newPeak = peak - loss;
                    if (newPeak <= 0)
                        continue;
                    if (newPeak * 3 <= 255 * scale * 2) {
                        nr = (r * newPeak) / peak;
                        ng = (g * newPeak) / peak;
                        nb = (b * newPeak) / peak;
                    }
                }
                if ((nr | ng | nb) == 0)
                    continue;

                int nCell = field.get(nx, ny, nz);
                long nKey = PosKey.pack(nx, ny, nz);
                long nWork = working.get(nKey, 0L); // this pass's own value: nothing yet
                int cr = eighthsR(nWork), cg = eighthsG(nWork), cb = eighthsB(nWork);

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;
                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    working.put(nKey, packEighths(fr, fg, fb));

                    // the stored cell shows the maximum of everything that reached it
                    int sr = nCell & 0xFF, sg = (nCell >> 8) & 0xFF, sb = (nCell >> 16) & 0xFF;
                    int mr = Math.max(sr, ColorLightUtil.clamp(fromWorking(fr)));
                    int mg = Math.max(sg, ColorLightUtil.clamp(fromWorking(fg)));
                    int mb = Math.max(sb, ColorLightUtil.clamp(fromWorking(fb)));
                    int merged = ColorLightUtil.pack(mr, mg, mb);
                    if (merged != (nCell & RGB_MASK)) {
                        field.set(nx, ny, nz, (nCell & FLAG_MASK) | merged);
                    }
                    queue.add(nKey);
                }
            }
        }
    }

    /** Whole-unit colour of a stored cell value -&gt; working units. */
    public long toWorking(int packedByteColor) {
        return packEighths(
                (packedByteColor & 0xFF) * scale,
                ((packedByteColor >> 8) & 0xFF) * scale,
                ((packedByteColor >> 16) & 0xFF) * scale);
    }

    /** One channel in working units -&gt; whole units, rounded and clamped. */
    public int fromWorking(int value) {
        return ColorLightUtil.clamp(Math.round(value / (float) scale));
    }
}
