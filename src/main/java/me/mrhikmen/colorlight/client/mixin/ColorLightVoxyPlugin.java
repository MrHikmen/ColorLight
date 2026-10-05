package me.mrhikmen.colorlight.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * Gate for {@code colorlight.voxy.mixins.json}: only applied when Voxy is installed and is a version the LOD shader
 * patch was written against. {@code -Dcolorlight.noVoxy=true} switches it off, {@code -Dcolorlight.forceVoxy=true}
 * applies it to any Voxy version (the shader patch still checks its anchors before changing anything).
 */
public final class ColorLightVoxyPlugin extends AbstractGateMixinPlugin {

    private static final String SUPPORTED_VOXY_PREFIX = "0.2.";

    @Override
    protected boolean decide() {
        if (Boolean.getBoolean("colorlight.noVoxy"))
            return false;

        Optional<ModContainer> voxy = FabricLoader.getInstance().getModContainer("voxy");
        if (voxy.isEmpty())
            return false;

        if (Boolean.getBoolean("colorlight.forceVoxy"))
            return true;

        return voxy.get().getMetadata().getVersion().getFriendlyString().startsWith(SUPPORTED_VOXY_PREFIX);
    }
}
