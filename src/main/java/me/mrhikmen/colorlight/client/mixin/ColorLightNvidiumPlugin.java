package me.mrhikmen.colorlight.client.mixin;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Gate for {@code colorlight.nvidium.mixins.json}: only applied when Nvidium is installed. The mixins target Nvidium's
 * classes by name and patch its shader text, checking their anchors before changing anything.
 * {@code -Dcolorlight.noNvidium=true} switches them off.
 */
public final class ColorLightNvidiumPlugin extends AbstractGateMixinPlugin {

    @Override
    protected boolean decide() {
        if (Boolean.getBoolean("colorlight.noNvidium") || Boolean.getBoolean("colorlight.noGpu"))
            return false;
        return FabricLoader.getInstance().isModLoaded("nvidium");
    }
}
