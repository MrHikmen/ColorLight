/**
 * How coloured light spreads through the world. There are two interchangeable models; a light source
 * uses whichever it was added with (blocks use the mode chosen in the settings).
 *
 * <h2>{@link me.mrhikmen.colorlight.core.light.propagation.GridPropagator GRID} - diamond (ромб)</h2>
 * Light hops between the 6 face-adjacent blocks and loses the same amount at every hop, so the reach is measured
 * in blocks walked along the axes ("Manhattan distance"). Seen from above the lit area is a diamond, in 3D an
 * octahedron. Cheap, and it looks like vanilla block light.
 * <pre>
 *            1
 *          2 1 2
 *        3 2 1 2 3        light level falls by 1 per step along an axis;
 *      4 3 2 1 2 3 4      a diagonal costs two steps
 *        3 2 1 2 3
 *          2 1 2
 *            1
 * </pre>
 *
 * <h2>{@link me.mrhikmen.colorlight.core.light.propagation.SmoothPropagator SMOOTH} - circle (круг)</h2>
 * Light hops between all 26 surrounding blocks (faces, edges and corners) and loses an amount proportional to the real
 * distance travelled, so the reach is a Euclidean distance. Seen from above the lit area is a circle, in 3D a sphere.
 * More expensive (26 neighbours instead of 6), but round.
 * <pre>
 *          . 3 3 3 .
 *        3 3 2 2 2 3 3
 *        3 2 1 1 1 2 3      a diagonal costs about 1.4 steps, a corner about 1.7;
 *        3 2 1 0 1 2 3      the running value is kept in 1/8 units and rounded only when stored
 *        3 2 1 1 1 2 3
 *        3 3 2 2 2 3 3
 *          . 3 3 3 .
 * </pre>
 *
 * Both models spread through a {@link me.mrhikmen.colorlight.core.light.propagation.LightField}, so they know nothing
 * about how or where the light is stored: the static block light of the world and the private field of a moving
 * (entity) light both use exactly the same code. In both models a block with opacity 15 stops the light and a block
 * with a higher opacity makes it fade faster.
 */
package me.mrhikmen.colorlight.core.light.propagation;
