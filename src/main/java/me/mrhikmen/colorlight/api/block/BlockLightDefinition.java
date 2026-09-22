package me.mrhikmen.colorlight.api.block;

import me.mrhikmen.colorlight.config.BlockSettings;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Immutable description of how one block should light up: which block, what colour, how strong, and
 * (optionally) which registered {@link me.mrhikmen.colorlight.api.propagation.PropagationMethod} it
 * spreads with. This is the programmatic counterpart of one entry a player can create by hand in
 * ColorLight's config/GUI ({@link BlockSettings}) - use it from your own mod to ship sensible defaults
 * for your blocks without asking players to edit JSON.
 * <p>
 * Example, registering a torch-like block that spreads in a diamond shape:
 * <pre>{@code
 * ColorLightBlockAPI.register(
 *         BlockLightDefinition.of(Identifier.fromNamespaceAndPath("mymod", "torch"), 255, 140, 60, 14)
 *                 .withPropagation(PropagationMethodRegistry.GRID));
 * }</pre>
 */
public final class BlockLightDefinition {

    private final Identifier block;
    private final int r;
    private final int g;
    private final int b;
    private final int light;
    private final Identifier propagationMethod;

    private BlockLightDefinition(Identifier block, int r, int g, int b, int light, Identifier propagationMethod) {
        this.block = Objects.requireNonNull(block, "block");
        this.r = r;
        this.g = g;
        this.b = b;
        this.light = light;
        this.propagationMethod = propagationMethod;
    }

    /** A definition with no propagation override - the block spreads with the engine-wide default. */
    public static BlockLightDefinition of(Identifier block, int r, int g, int b, int light) {
        return new BlockLightDefinition(block, r, g, b, light, null);
    }

    /** Copy of this definition that spreads with {@code method} instead (e.g. {@code PropagationMethodRegistry.SMOOTH}). */
    public BlockLightDefinition withPropagation(Identifier method) {
        return new BlockLightDefinition(block, r, g, b, light, method);
    }

    public Identifier block() {
        return block;
    }

    public int r() {
        return r;
    }

    public int g() {
        return g;
    }

    public int b() {
        return b;
    }

    public int light() {
        return light;
    }

    /** {@code null} means "use the engine's default propagation method". */
    public Identifier propagationMethod() {
        return propagationMethod;
    }

    /** Converts to the config-facing {@link BlockSettings} shape the light registry actually consumes. */
    public BlockSettings toBlockSettings() {
        BlockSettings settings = new BlockSettings(block, light, true);
        settings.r = r;
        settings.g = g;
        settings.b = b;
        settings.propagation = (propagationMethod != null) ? propagationMethod.toString() : "";
        return settings;
    }
}
