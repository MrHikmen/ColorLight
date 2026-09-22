package me.mrhikmen.colorlight.core.light.propagation;

import me.mrhikmen.colorlight.core.light.util.LongQueue;

/**
 * A model for how light spreads outwards from cells that already hold light.
 *
 * @see GridPropagator diamond-shaped spread
 * @see SmoothPropagator circle-shaped spread
 */
public interface LightPropagator {

    /**
     * Spreads light from every cell in {@code queue} (keys built with {@code PosKey.pack}) until nothing brighter can be
     * reached. The queue is consumed. Cells that end up brighter are written through {@code field}.
     */
    void propagate(LongQueue queue, LightField field);
}
