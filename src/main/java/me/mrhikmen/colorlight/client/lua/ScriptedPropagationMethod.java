package me.mrhikmen.colorlight.client.lua;

import me.mrhikmen.colorlight.api.propagation.PropagationMethod;
import me.mrhikmen.colorlight.client.core.light.propagation.LightPropagator;
import me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator;

import net.minecraft.resources.Identifier;

/** A {@link PropagationMethod} whose behaviour comes from a {@link PropagationSpec} (a Lua file in a resource pack). */
public final class ScriptedPropagationMethod implements PropagationMethod {

    private final Identifier id;
    private final PropagationSpec spec;
    private final String source;

    /**
     * @param source where it came from, e.g. {@code "resource pack 'Fancy Light' (colorlight:propagation/ring.lua)"},
     *               or {@code "built-in fallback"}
     */
    public ScriptedPropagationMethod(Identifier id, PropagationSpec spec, String source) {
        this.id = id;
        this.spec = spec;
        this.source = source;
    }

    @Override
    public Identifier id() {
        return id;
    }

    @Override
    public LightPropagator create(float decayPerOpacityUnit) {
        return spec.createPropagator(decayPerOpacityUnit);
    }

    @Override
    public TablePropagator createTable(float decayPerOpacityUnit) {
        return spec.createTable(decayPerOpacityUnit);
    }

    @Override
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
