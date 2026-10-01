/**
 * How coloured light spreads through the world.
 * <p>
 * <b>The way light spreads is not written here - it is data in the resource pack.</b> A propagation method is a Lua
 * file at {@code assets/colorlight/propagation/<name>.lua} (see the {@code lua} package and
 * {@code docs/LUA_API.md}); ColorLight's own diamond ("grid") and circle ("smooth") shapes are just two such files
 * shipped in the mod's built-in pack, and any resource pack can replace them or add its own.
 * <p>
 * Two kinds of method exist, both ending up as a {@link me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator}:
 * <ul>
 *     <li><b>table-driven</b> (the normal case) - the script describes the neighbours, the cost of each hop and the
 *     precision; {@link me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator} runs the flood in Java, so
 *     it is exactly as fast as the old hard-coded models;</li>
 *     <li><b>fully scripted</b> - the script implements {@code propagate(field, queue)} itself in Lua. Maximum
 *     freedom, but interpreted, so noticeably slower; meant for small special effects.</li>
 * </ul>
 * Both spread through a {@link me.mrhikmen.colorlight.client.core.light.propagation.LightField}, so they know nothing about
 * how or where the light is stored: the static block light of the world and the private field of a moving (entity)
 * light use exactly the same code. A block with opacity 15 (or the script's {@code max_opacity}) stops the light.
 */
package me.mrhikmen.colorlight.client.core.light.propagation;
