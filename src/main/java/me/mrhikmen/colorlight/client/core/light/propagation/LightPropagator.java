package me.mrhikmen.colorlight.client.core.light.propagation;

import me.mrhikmen.colorlight.client.core.light.util.LongQueue;

/**
 * A model for how light spreads outwards from cells that already hold light.
 *
 * @see TablePropagator the data-driven kernel behind every propagation method (grid, smooth, and any JSON file
 *      a resource pack supplies)
 */
public interface LightPropagator {

    /**
     * Spreads light from every cell in {@code queue} (keys built with {@code PosKey.pack}) until nothing brighter can be
     * reached. The queue is consumed. Cells that end up brighter are written through {@code field}.
     */
    void propagate(LongQueue queue, LightField field);

    /**
     * Like {@link #propagate}, but the light of this pass is followed on its own: what other passes (other colours) left
     * in the cells is not mixed into what this pass carries on. Cells still end up with the per-channel maximum of the
     * stored light and this pass's light. Used so that overlapping lights of different colours fade independently and
     * only mix where they meet, instead of one light carrying the other's colour along as it spreads.
     */
    default void propagateIsolated(LongQueue queue, LightField field) {
        propagate(queue, field);
    }
}
