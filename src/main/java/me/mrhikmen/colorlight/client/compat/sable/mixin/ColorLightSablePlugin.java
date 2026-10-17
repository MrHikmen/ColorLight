package me.mrhikmen.colorlight.client.compat.sable.mixin;

import me.mrhikmen.colorlight.client.compat.AbstractGateMixinPlugin;
import net.fabricmc.loader.api.FabricLoader;

/** Gate for {@code colorlight.sable.mixins.json}: only applied when Sable is installed (and the GPU pipeline is not disabled). */
public final class ColorLightSablePlugin extends AbstractGateMixinPlugin {

    @Override
    protected boolean decide() {
        return !Boolean.getBoolean("colorlight.noGpu") && FabricLoader.getInstance().isModLoaded("sable");
    }
}
