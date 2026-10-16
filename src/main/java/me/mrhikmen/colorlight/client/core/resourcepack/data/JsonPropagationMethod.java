package me.mrhikmen.colorlight.client.core.resourcepack.data;

import me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator;
import me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator;

import net.minecraft.resources.ResourceLocation;

/**
 * A model for how coloured light spreads outward from a source, defined by a {@link PropagationSpec}: plain data from
 * a JSON file in a resource pack, no code. A file {@code assets/colorlight/propagation/<name>.json} becomes the
 * method {@code colorlight:<name>} in {@link me.mrhikmen.colorlight.client.core.light.propagation.PropagationMethodRegistry}:
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
 * See {@code examples/API.md} for every field.
 */
public final class JsonPropagationMethod {

    private final ResourceLocation id;
    private final PropagationSpec spec;
    private final String source;

    /**
     * @param source where it came from, e.g. {@code "resource pack 'Fancy Light' (colorlight:propagation/ring.json)"},
     *               or {@code "built-in fallback"}
     */
    public JsonPropagationMethod(ResourceLocation id, PropagationSpec spec, String source) {
        this.id = id;
        this.spec = spec;
        this.source = source;
    }

    /** Stable id this method is referenced by (block JSON files, configs, the registry key), e.g. {@code colorlight:beam}. */
    public ResourceLocation id() {
        return id;
    }

    /**
     * Builds the propagator that spreads the light for one engine instance.
     *
     * @param decayPerOpacityUnit light units lost per step through a block of opacity 0, the same quantity the
     *                            {@code loss} formula receives as {@code decay} ({@code 255 / rangeInBlocks})
     */
    public LightPropagator create(float decayPerOpacityUnit) {
        return spec.createPropagator(decayPerOpacityUnit);
    }

    /** The table-driven kernel of this method; moving (entity) lights need its sub-block precision. */
    public TablePropagator createTable(float decayPerOpacityUnit) {
        return spec.createTable(decayPerOpacityUnit);
    }

    /** Human-readable name for GUIs/logs (the file's {@code "name"}). */
    public String displayName() {
        return spec.name;
    }

    public PropagationSpec spec() {
        return spec;
    }

    public String source() {
        return source;
    }
}
