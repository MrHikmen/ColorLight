package me.mrhikmen.colorlight.api.propagation.builtin;

import me.mrhikmen.colorlight.api.propagation.PropagationMethod;
import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.core.light.propagation.LightPropagator;
import me.mrhikmen.colorlight.core.light.propagation.SmoothPropagator;

import net.minecraft.resources.Identifier;

/** ColorLight's own round spread ({@link SmoothPropagator}), registered through the public API. */
public final class SmoothPropagationMethod implements PropagationMethod {

    @Override
    public Identifier id() {
        return PropagationMethodRegistry.SMOOTH;
    }

    @Override
    public LightPropagator create(float decayPerOpacityUnit) {
        return new SmoothPropagator(decayPerOpacityUnit);
    }

    @Override
    public String displayName() {
        return "Smooth (circle)";
    }
}
