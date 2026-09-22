package me.mrhikmen.colorlight.core.light.propagation;

/**
 * What a {@link LightPropagator} needs from the place the light is stored. Implemented by the engine for the
 * world's static light and by the dynamic-light flood for the private field of one moving light.
 * <p>
 * A cell value holds the colour in its low 24 bits ({@code b << 16 | g << 8 | r}); the top byte belongs to the
 * owner (the engine keeps flags there) and must be handed back unchanged when a colour is written.
 */
public interface LightField {

    /** A propagation pass is about to start: forget anything remembered about block opacity. */
    void beginPass();

    /** The pass has finished: the implementation may release scratch memory. */
    void endPass();

    /** Light dampening of the block at the position, 0 (air) to 15 (fully opaque, blocks light). */
    int opacityAt(int x, int y, int z);

    /** Stored value of the cell, 0 if nothing was ever written there. */
    int get(int x, int y, int z);

    /** Stores a new value. Only called when the colour actually changed, so this is where "dirty" is recorded. */
    void set(int x, int y, int z, int value);
}
