/**
 * Public API for other mods to register default coloured-light settings for their own blocks - which
 * block, what RGB colour, how strong, and (optionally) which
 * {@link me.mrhikmen.colorlight.api.propagation.PropagationMethod} it spreads with - the same three
 * things a player fills in by hand in ColorLight's block config/GUI
 * ({@link me.mrhikmen.colorlight.config.BlockSettings}), but supplied programmatically so players don't
 * have to.
 * <p>
 * Build a {@link me.mrhikmen.colorlight.api.block.BlockLightDefinition} and hand it to
 * {@link me.mrhikmen.colorlight.api.block.ColorLightBlockAPI#register}:
 * <pre>{@code
 * ColorLightBlockAPI.register(
 *         BlockLightDefinition.of(Identifier.fromNamespaceAndPath("mymod", "torch"), 255, 140, 60, 14)
 *                 .withPropagation(PropagationMethodRegistry.GRID)); // "diamond", as in the example
 * }</pre>
 * These act as defaults: if a player's own config already has an entry for the same block, theirs wins
 * (colour, strength, enabled state and propagation are all still editable per block from ColorLight's
 * GUI, whether the entry came from a player or from an addon).
 */
package me.mrhikmen.colorlight.api.block;
