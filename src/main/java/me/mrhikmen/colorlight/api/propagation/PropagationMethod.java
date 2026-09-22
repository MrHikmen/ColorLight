package me.mrhikmen.colorlight.api.propagation;

import me.mrhikmen.colorlight.core.light.propagation.LightField;
import me.mrhikmen.colorlight.core.light.propagation.LightPropagator;

import net.minecraft.resources.Identifier;

/**
 * A pluggable model for how coloured light spreads outward from a source, registered with
 * {@link PropagationMethodRegistry} so it can be picked per block (see {@link me.mrhikmen.colorlight.config.BlockSettings#propagation})
 * or by another mod through {@link me.mrhikmen.colorlight.api.block.ColorLightBlockAPI}.
 * <p>
 * This is a thin descriptor: {@link #id()} is what configs and other mods reference, and
 * {@link #create(float)} builds the actual {@link LightPropagator} that does the flood-filling for
 * one engine instance. The engine creates one propagator per method it actually needs and reuses it
 * for every source that asks for that method, so implementations only need to be stateless apart from
 * scratch memory that is safe to share across sources (exactly like the built-in
 * {@link me.mrhikmen.colorlight.core.light.propagation.GridPropagator GridPropagator} and
 * {@link me.mrhikmen.colorlight.core.light.propagation.SmoothPropagator SmoothPropagator} already do).
 *
 * <h2>Writing your own method</h2>
 * Implement {@link LightPropagator#propagate(me.mrhikmen.colorlight.core.light.util.LongQueue, LightField)}:
 * poll seed positions from the queue, look up neighbours through the given {@link LightField} (which
 * hides whether the light lives in the world's static field or a moving light's private one), and write
 * back cells that ended up brighter, re-queuing them so the flood continues. See
 * {@code GridPropagator} for the simplest complete example (6 face neighbours, whole-unit decay) and
 * {@code SmoothPropagator} for a more elaborate one (26 neighbours, sub-unit precision).
 * <pre>{@code
 * public final class ColumnPropagator implements LightPropagator {
 *     private final int decayPerBlock;
 *
 *     public ColumnPropagator(float decayPerOpacityUnit) {
 *         this.decayPerBlock = Math.max(1, Math.round(decayPerOpacityUnit));
 *     }
 *
 *     @Override
 *     public void propagate(LongQueue queue, LightField field) {
 *         // only ever hop straight up/down - a "beam" shape
 *         ...
 *     }
 * }
 *
 * public final class ColumnPropagationMethod implements PropagationMethod {
 *     public static final Identifier ID = Identifier.fromNamespaceAndPath("mymod", "column");
 *
 *     @Override public Identifier id() { return ID; }
 *     @Override public LightPropagator create(float decayPerOpacityUnit) { return new ColumnPropagator(decayPerOpacityUnit); }
 *     @Override public String displayName() { return "Column"; }
 * }
 *
 * // once, during your mod's init:
 * PropagationMethodRegistry.register(new ColumnPropagationMethod());
 * }</pre>
 * A block can then request it in its config entry: {@code "propagation": "mymod:column"}, or another
 * mod can register it programmatically via {@link me.mrhikmen.colorlight.api.block.ColorLightBlockAPI}.
 */
public interface PropagationMethod {

    /**
     * Stable id other code references this method by - in block configs, in
     * {@link me.mrhikmen.colorlight.api.block.ColorLightBlockAPI} calls, and as the registry's key.
     * Namespace it under your own mod id to avoid clashing with other addons.
     */
    Identifier id();

    /**
     * Builds the propagator that actually spreads the light for one engine instance.
     *
     * @param decayPerOpacityUnit light units lost per step through a block of opacity 0, the same
     *                            quantity the built-in propagators take (derived from the configured
     *                            light range: {@code 255 / rangeInBlocks})
     */
    LightPropagator create(float decayPerOpacityUnit);

    /** Human-readable name for GUIs/logs. Defaults to {@link #id()}; override for a nicer label. */
    default String displayName() {
        return id().toString();
    }
}
