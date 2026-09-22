package me.mrhikmen.colorlight.api.propagation.builtin;

import me.mrhikmen.colorlight.api.propagation.PropagationMethod;
import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.core.light.propagation.GridPropagator;
import me.mrhikmen.colorlight.core.light.propagation.LightPropagator;

import net.minecraft.resources.Identifier;

/** ColorLight's own diamond-shaped spread ({@link GridPropagator}), registered through the public API. */
public final class GridPropagationMethod implements PropagationMethod {

    @Override
    public Identifier id() {
        return PropagationMethodRegistry.GRID;
    }

    @Override
    public LightPropagator create(float decayPerOpacityUnit) {
        return new GridPropagator(decayPerOpacityUnit);
    }

    @Override
    public String displayName() {
        return "Grid (diamond)";
    }
}
