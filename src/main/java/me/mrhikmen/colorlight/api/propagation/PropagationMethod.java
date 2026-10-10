package me.mrhikmen.colorlight.api.propagation;

import me.mrhikmen.colorlight.client.core.light.propagation.LightField;
import me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator;
import me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator;

import net.minecraft.resources.Identifier;

/**
 * A pluggable model for how coloured light spreads outward from a source, registered with
 * {@link PropagationMethodRegistry} so it can be picked per block (in a block JSON file, in the config, or by another
 * mod through {@link me.mrhikmen.colorlight.api.block.ColorLightBlockAPI}).
 *
 * <h2>Normally you don't implement this - you write a JSON file</h2>
 * ColorLight's own shapes are plain resource-pack data, no code. A file {@code assets/colorlight/propagation/<name>.json}
 * becomes the method {@code colorlight:<name>}:
 * <pre>{@code
 * // assets/colorlight/propagation/beam.json  ->  "colorlight:beam"
 * {
 *     "name": "Vertical beam",
 *     "scale": 1,
 *     "neighbors": [[0,1,0], [0,-1,0], [1,0,0], [-1,0,0], [0,0,1], [0,0,-1]],
 *     "loss": {
 *         "vertical_factor": 0.25,     // carries far vertically
 *         "horizontal_factor": 2.0     // but barely sideways
 *     }
 * }
 * }</pre>
 * See {@code examples/API.md} for every field. The loss formula is a small set of numeric weights (distance,
 * opacity, vertical/horizontal, an overall multiplier) - there is no scripting engine, so a resource pack can
 * only ever supply data, never code.
 *
 * <h2>Registering one from Java</h2>
 * A mod can still register a method in code - implement {@link LightPropagator#propagate} (poll seed positions from the
 * queue, look up neighbours through the {@link LightField}, write back cells that ended up brighter and re-queue
 * them) and call {@link PropagationMethodRegistry#register(PropagationMethod)} during client init. A method registered
 * in code survives resource reloads; methods coming from a resource pack's JSON files are replaced by them.
 */
public interface PropagationMethod {

    /**
     * Stable id other code references this method by - in block scripts, configs and
     * {@link me.mrhikmen.colorlight.api.block.ColorLightBlockAPI} calls, and as the registry's key.
     * Namespace it under your own mod id / pack namespace to avoid clashing with others.
     */
    Identifier id();

    /**
     * Builds the propagator that actually spreads the light for one engine instance.
     *
     * @param decayPerOpacityUnit light units lost per step through a block of opacity 0, the same quantity a
     *                            propagation JSON file's {@code loss} formula receives as {@code decay} (derived
     *                            from the configured light range: {@code 255 / rangeInBlocks})
     */
    LightPropagator create(float decayPerOpacityUnit);

    /**
     * The table-driven kernel of this method, or {@code null} if it is not table-driven. Moving (entity) lights
     * need the sub-block precision and the per-cell values only a {@link TablePropagator} offers; for a method that
     * returns {@code null} here they fall back to the default one.
     */
    default TablePropagator createTable(float decayPerOpacityUnit) {
        return null;
    }

    /** Human-readable name for GUIs/logs. Defaults to {@link #id()}; override for a nicer label. */
    default String displayName() {
        return id().toString();
    }
}
