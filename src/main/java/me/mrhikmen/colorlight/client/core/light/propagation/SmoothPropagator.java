package me.mrhikmen.colorlight.client.core.light.propagation;

import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.util.LongQueue;
import me.mrhikmen.colorlight.client.core.light.util.PassMap;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.*;

/**
 * <b>SMOOTH - circle (круг).</b> Light hops between all 26 surrounding blocks (faces, edges and corners) and loses an
 * amount proportional to the real distance travelled, scaled by the opacity of the block it enters. The reach is a
 * Euclidean distance, which makes the lit area round (a sphere in 3D). Costs roughly four times the work of
 * {@link GridPropagator}.
 * <p>
 * While spreading, the running value of every touched cell is kept in 1/8 light units and only rounded to whole
 * units when it is written through the {@link LightField}, so rounding errors don't pile up along a path.
 * Those working values are also readable afterwards ({@link #eighthsAt}, {@link #collectKeys}), which the dynamic-light
 * flood uses to keep the full precision of a moving light.
 * <p>
 * Holds scratch memory: not thread-safe, use one instance per thread.
 */
public final class SmoothPropagator implements LightPropagator {

    private final int[][] decay;

    /** Working values (1/8 units) of the cells touched during the current pass. */
    private final PassMap eighths = new PassMap(1 << 12);

    /** @param decayPerOpacityUnit light units lost per unit of distance through a block of opacity 0 (255 / range in blocks) */
    public SmoothPropagator(float decayPerOpacityUnit) {
        this.decay = buildSmoothDecay(decayPerOpacityUnit, FIXED_POINT_SCALE);
    }

    // ---- working values: for callers that seed / read them directly (dynamic lights) ----

    /** Forgets the working values of the previous pass. {@link #propagate} does this itself. */
    public void beginPass() {
        eighths.clear();
    }

    /** Gives a cell a starting value (packed with {@link PropagationTables#packEighths}) before {@link #run}. */
    public void seed(long key, long packedEighths) {
        eighths.put(key, packedEighths);
    }

    /** Working value of a cell in the current pass, or 0 if the pass never touched it. */
    public long eighthsAt(long key) {
        return eighths.get(key, 0L);
    }

    /** Number of cells the current pass has a value for. */
    public int cellCount() {
        return eighths.size();
    }

    /** Copies the keys of those cells into {@code out} (at least {@link #cellCount()} long); returns how many. */
    public int collectKeys(long[] out) {
        return eighths.collectKeys(out);
    }

    /** Releases the scratch memory if a huge pass left it oversized. */
    public void trim() {
        eighths.trim();
    }

    // ---- the spreading itself ----

    @Override
    public void propagate(LongQueue queue, LightField field) {
        field.beginPass();
        beginPass();

        run(queue, field);

        field.endPass();
        trim();
    }

    /** The flood-fill proper, without touching the working values first (so they can be seeded beforehand). */
    public void run(LongQueue queue, LightField field) {
        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);

            long curEighths = eighths.get(key, -1L);
            if (curEighths < 0) {
                curEighths = toEighths(field.get(x, y, z));
                eighths.put(key, curEighths);
            }

            int r = eighthsR(curEighths), g = eighthsG(curEighths), b = eighthsB(curEighths);
            if ((r | g | b) == 0)
                continue;

            for (int n = 0; n < SMOOTH_COUNT; n++) {
                int nx = x + SMOOTH_DX[n], ny = y + SMOOTH_DY[n], nz = z + SMOOTH_DZ[n];

                int opacity = field.opacityAt(nx, ny, nz);
                if (opacity >= MAX_OPACITY)
                    continue;

                int loss = decay[n][opacity];
                int nr = r - loss; if (nr < 0) nr = 0;
                int ng = g - loss; if (ng < 0) ng = 0;
                int nb = b - loss; if (nb < 0) nb = 0;
                if ((nr | ng | nb) == 0)
                    continue;

                long nKey = PosKey.pack(nx, ny, nz);
                int nCell = field.get(nx, ny, nz);

                long nEighths = eighths.get(nKey, -1L);
                if (nEighths < 0)
                    nEighths = toEighths(nCell);

                int cr = eighthsR(nEighths), cg = eighthsG(nEighths), cb = eighthsB(nEighths);

                boolean changed = false;
                int fr = cr, fg = cg, fb = cb;
                if (nr > cr) { fr = nr; changed = true; }
                if (ng > cg) { fg = ng; changed = true; }
                if (nb > cb) { fb = nb; changed = true; }

                if (changed) {
                    eighths.put(nKey, packEighths(fr, fg, fb));

                    int rounded = ColorLightUtil.pack(fromEighths(fr), fromEighths(fg), fromEighths(fb));
                    if (rounded != (nCell & RGB_MASK)) {
                        field.set(nx, ny, nz, (nCell & FLAG_MASK) | rounded);
                    }
                    queue.add(nKey);
                }
            }
        }
    }

    /** Whole-unit colour of a stored cell value -> 1/8 units. */
    public static long toEighths(int packedByteColor) {
        return packEighths(
                (packedByteColor & 0xFF) * FIXED_POINT_SCALE,
                ((packedByteColor >> 8) & 0xFF) * FIXED_POINT_SCALE,
                ((packedByteColor >> 16) & 0xFF) * FIXED_POINT_SCALE);
    }

    /** One channel in 1/8 units -> whole units, rounded and clamped. */
    public static int fromEighths(int eighthsValue) {
        return ColorLightUtil.clamp(Math.round(eighthsValue / (float) FIXED_POINT_SCALE));
    }
}
