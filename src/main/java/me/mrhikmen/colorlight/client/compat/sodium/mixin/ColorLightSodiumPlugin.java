package me.mrhikmen.colorlight.client.compat.sodium.mixin;

import me.mrhikmen.colorlight.client.compat.AbstractGateMixinPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * Gate for {@code colorlight.sodium.mixins.json}: those mixins are tied to Sodium's internal shader and renderer code,
 * so they are only applied to a Sodium version they were written against. Anything else just leaves them out and
 * ColorLight keeps using vertex colours, which is version independent.
 * <p>
 * Escape hatches: {@code -Dcolorlight.noGpu=true} skips them entirely; {@code -Dcolorlight.forceGpu=true} applies them
 * to any Sodium version.
 */
public final class ColorLightSodiumPlugin extends AbstractGateMixinPlugin {

    /** Sodium versions the GPU pipeline was written against: major.minor prefix plus the Minecraft version. */
    private static final String SUPPORTED_SODIUM_PREFIX = "0.8.";
    private static final boolean VERIFIED = true;
    private static final String SUPPORTED_MINECRAFT_TAG = "mc1.21.1";

    @Override
    protected boolean decide() {
        if (Boolean.getBoolean("colorlight.noGpu"))
            return false;

        Optional<ModContainer> sodium = FabricLoader.getInstance().getModContainer("sodium");
        if (sodium.isEmpty())
            return false;

        if (Boolean.getBoolean("colorlight.forceGpu"))
            return true;

        // 1.21.1 / Sodium 0.8.13: the shader copy in assets/colorlight/shaders/blocks has been derived from
        // Sodium 0.8.13's block_layer_opaque.* for 1.21.1 (float v_FragDistance, u_FogStart/u_FogEnd/u_FogShape,
        // MAX_TEXTURE_LOD_BIAS). Set VERIFIED to false to make the pipeline opt-in (-Dcolorlight.forceGpu=true) again.
        if (!VERIFIED)
            return false;

        String version = sodium.get().getMetadata().getVersion().getFriendlyString();
        return version.startsWith(SUPPORTED_SODIUM_PREFIX) && version.contains(SUPPORTED_MINECRAFT_TAG);
    }
}
