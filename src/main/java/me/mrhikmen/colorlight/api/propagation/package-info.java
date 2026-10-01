/**
 * Public API for light-spreading shapes.
 * <p>
 * The primary way to add one is a Lua file in a resource pack
 * ({@code assets/colorlight/propagation/<name>.lua}, see {@code docs/LUA_API.md}) - that is also how ColorLight's
 * own diamond ("grid") and circle ("smooth") are defined. From Java, implement
 * {@link me.mrhikmen.colorlight.api.propagation.PropagationMethod} and make it known via
 * {@link me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry}:
 * <pre>{@code
 * PropagationMethodRegistry.register(new MyPropagationMethod());
 * }</pre>
 * Either way the method is referred to by its id: a block script sets {@code propagation = "colorlight:beam"}, a player names
 * it in the config, or your mod passes it to {@link me.mrhikmen.colorlight.api.block.ColorLightBlockAPI}.
 */
package me.mrhikmen.colorlight.api.propagation;
