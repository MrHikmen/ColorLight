/**
 * Public API for adding new light-spreading shapes to ColorLight.
 * <p>
 * A propagation method is anything implementing
 * {@link me.mrhikmen.colorlight.core.light.propagation.LightPropagator} (how the flood-fill works)
 * described by a {@link me.mrhikmen.colorlight.api.propagation.PropagationMethod} (its id and how to
 * build it) and made known to the mod via {@link me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry}.
 * <p>
 * ColorLight's own two shapes - diamond ("grid") and circle ("smooth") - are implemented in
 * {@link me.mrhikmen.colorlight.api.propagation.builtin} using this exact same API, so they double as
 * a worked example: read {@code GridPropagationMethod}/{@code SmoothPropagationMethod} alongside
 * {@link me.mrhikmen.colorlight.api.propagation.PropagationMethod}'s Javadoc to see the whole shape of
 * an integration, then register your own the same way, from your mod's init:
 * <pre>{@code
 * PropagationMethodRegistry.register(new MyPropagationMethod());
 * }</pre>
 * Once registered, a block can be made to use it either by a player/pack author naming its id in the
 * block's config entry ({@link me.mrhikmen.colorlight.config.BlockSettings#propagation}), or by your
 * own mod registering that block through {@link me.mrhikmen.colorlight.api.block.ColorLightBlockAPI}.
 */
package me.mrhikmen.colorlight.api.propagation;
