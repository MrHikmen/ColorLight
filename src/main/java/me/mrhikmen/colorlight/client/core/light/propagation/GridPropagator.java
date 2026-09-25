package me.mrhikmen.colorlight.client.core.light.propagation;

import me.mrhikmen.colorlight.client.core.light.util.LongQueue;
import me.mrhikmen.colorlight.client.core.light.util.PosKey;

import static me.mrhikmen.colorlight.client.core.light.propagation.PropagationTables.*;

/**
 * <b>GRID - diamond (ромб).</b> Light hops between the 6 face-adjacent blocks and loses the same whole number of
 * units at every hop, scaled by the opacity of the block it enters. The reach is therefore counted in steps along the
 * axes, which makes the lit area a diamond (an octahedron in 3D). This is the default model and the cheapest one.
 * <p>
 * Stateless apart from its decay table, so one instance can serve any number of fields.
 */
public final class GridPropagator implements LightPropagator {

    private final int[] decay;

    /** @param decayPerOpacityUnit light units lost per hop through a block of opacity 0 (255 / range in blocks) */
    public GridPropagator(float decayPerOpacityUnit) {
        this.decay = buildGridDecay(decayPerOpacityUnit);
    }

    @Override
    public void propagate(LongQueue queue, LightField field) {
        field.beginPass();

        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = PosKey.x(key), y = PosKey.y(key), z = PosKey.z(key);

            int cur = field.get(x, y, z);
            int r = cur & 0xFF, g = (cur >> 8) & 0xFF, b = (cur >> 16) & 0xFF;
            if ((r | g | b) == 0)
                continue;

            for (int n = 0; n < 6; n++) {
                int nx = x + GRID_DX[n], ny = y + GRID_DY[n], nz = z + GRID_DZ[n];

                int opacity = field.opacityAt(nx, ny, nz);
                if (opacity >= MAX_OPACITY)
                    continue;

                int loss = decay[opacity];
                int nr = r - loss; if (nr < 0) nr = 0;
                int ng = g - loss; if (ng < 0) ng = 0;
                int nb = b - loss; if (nb < 0) nb = 0;
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

        field.endPass();
    }
}
