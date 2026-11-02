package me.mrhikmen.colorlight.client.core.light.propagation;

/**
 * What a {@link LightPropagator} needs from the place the light is stored. Implemented by the engine for the
 * world's static light and by the dynamic-light flood for the private field of one moving light.
 * <p>
 * Light is stored per colour: a cell holds a brightness (1..255) for each of up to two colours, named by their
 * palette id (see {@code LightCell}); how that is laid out is the field's business, not the propagator's.
 */
public interface LightField {

    /** A propagation pass is about to start: forget anything remembered about block opacity. */
    void beginPass();

    /** The pass has finished: the implementation may release scratch memory. */
    void endPass();

    /** Light dampening of the block at the position, 0 (air) to 15 (fully opaque, blocks light). */
    int opacityAt(int x, int y, int z);

    /**
     * The pass reached the cell with light of colour {@code id} at brightness {@code level} (1..255): make the cell
     * hold at least that much of the colour. Called only when the brightness is higher than at any earlier call of the
     * pass for this cell, so this is where "dirty" is recorded.
     */
    void raise(int x, int y, int z, int id, int level);
}
