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
}
