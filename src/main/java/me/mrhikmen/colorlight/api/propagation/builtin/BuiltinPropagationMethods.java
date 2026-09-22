package me.mrhikmen.colorlight.api.propagation.builtin;

import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;

/**
 * Registers ColorLight's own propagation methods (grid/diamond, smooth/circle) through the public
 * {@link PropagationMethodRegistry}, exactly like a third-party mod would register its own. Called once
 * from {@code ColorLightClient.onInitializeClient()}, before the config and the engine are set up.
 */
public final class BuiltinPropagationMethods {

    private static volatile boolean registered;

    public static synchronized void registerAll() {
        if (registered)
            return;

        PropagationMethodRegistry.register(new GridPropagationMethod());
        PropagationMethodRegistry.register(new SmoothPropagationMethod());

        registered = true;
    }

    private BuiltinPropagationMethods() {
    }
}
