package me.mrhikmen.colorlight.api.block;

import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where other mods register default coloured-light settings for their own blocks - block id, colour,
 * strength and (optionally) which {@link me.mrhikmen.colorlight.api.propagation.PropagationMethod} it
 * spreads with - without touching ColorLight's JSON config.
 * <p>
 * Call {@link #register} once during your mod's init, before
 * {@link me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry#load} runs (i.e. before or
 * from {@code ClientModInitializer.onInitializeClient}). A player's own config entry for the same block
 * always takes priority over an API-provided one, so a definition registered here is a <i>default</i>:
 * players can still override colour, strength, enable/disable or propagation for it from ColorLight's
 * own GUI, exactly like any block ColorLight auto-discovered itself.
 * <pre>{@code
 * @Override
 * public void onInitializeClient() {
 *     ColorLightBlockAPI.register(
 *             Identifier.fromNamespaceAndPath("mymod", "glowing_crystal"),
 *             120, 220, 255, 15,
 *             Identifier.fromNamespaceAndPath("mymod", "column")); // a method that mod itself registered
 * }
 * }</pre>
 */
public final class ColorLightBlockAPI {

    private static final Map<Identifier, BlockLightDefinition> DEFINITIONS = new ConcurrentHashMap<>();

    public static void register(BlockLightDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        DEFINITIONS.put(definition.block(), definition);
    }

    /** Convenience overload: no propagation override, the block uses the engine's default method. */
    public static void register(Identifier block, int r, int g, int b, int light) {
        register(BlockLightDefinition.of(block, r, g, b, light));
    }

    /** Convenience overload with an explicit propagation method, e.g. {@code PropagationMethodRegistry.SMOOTH}. */
    public static void register(Identifier block, int r, int g, int b, int light, Identifier propagationMethod) {
        register(BlockLightDefinition.of(block, r, g, b, light).withPropagation(propagationMethod));
    }

    public static void unregister(Identifier block) {
        if (block != null)
            DEFINITIONS.remove(block);
    }

    public static BlockLightDefinition get(Identifier block) {
        return (block != null) ? DEFINITIONS.get(block) : null;
    }

    /** Every definition currently registered by any mod. Read by {@code ColorLightBlockRegistry} at load time. */
    public static Collection<BlockLightDefinition> snapshot() {
        return List.copyOf(DEFINITIONS.values());
    }

    private ColorLightBlockAPI() {
    }
}
