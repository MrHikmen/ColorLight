/**
 * How coloured light spreads through the world.
 * <p>
 * <b>The way light spreads is not written here - it is data in the resource pack.</b> A propagation method is a JSON
 * file at {@code assets/colorlight/propagation/<name>.json} (see the {@code resourcepack.data} package and
 * {@code examples/API.md}); ColorLight's own diamond ("grid") and circle ("smooth") shapes are just two such files
 * shipped in the mod's built-in pack, and any resource pack can replace them or add its own.
 * <p>
 * A method ends up as a {@link me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator}: the JSON file names
 * the neighbours, the cost of each hop and the precision, and
 * {@link me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator} runs the flood in Java from that data,
 * so it is exactly as fast as the old hard-coded models. Methods are looked up by id through
 * {@link me.mrhikmen.colorlight.client.core.light.propagation.PropagationMethodRegistry}.
 * <p>
 * They spread through a {@link me.mrhikmen.colorlight.client.core.light.propagation.LightField}, so they know nothing about
 * how or where the light is stored: the static block light of the world and the private field of a moving (entity)
 * light use exactly the same code. A block with opacity 15 (or the JSON file's {@code max_opacity}) stops the light.
 */
package me.mrhikmen.colorlight.client.core.light.propagation;
