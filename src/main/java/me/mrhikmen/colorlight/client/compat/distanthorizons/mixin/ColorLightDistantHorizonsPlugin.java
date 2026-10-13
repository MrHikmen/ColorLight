package me.mrhikmen.colorlight.client.compat.distanthorizons.mixin;

import me.mrhikmen.colorlight.client.compat.AbstractGateMixinPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * Gate for {@code colorlight.distanthorizons.mixins.json}: only applied when Distant Horizons is installed and is
 * a version the shader patch was written against (3.x). {@code -Dcolorlight.noDistantHorizons=true} switches it
 * off, {@code -Dcolorlight.forceDistantHorizons=true} applies it to any Distant Horizons version (the shader patch
 * still checks its anchors before changing anything, and the mixin itself has {@code require = 0}).
 */
public final class ColorLightDistantHorizonsPlugin extends AbstractGateMixinPlugin {

    private static final String SUPPORTED_DH_PREFIX = "3.";

    @Override
    protected boolean decide() {
        if (Boolean.getBoolean("colorlight.noDistantHorizons"))
            return false;

        Optional<ModContainer> dh = FabricLoader.getInstance().getModContainer("distanthorizons");
        if (dh.isEmpty())
            return false;

        if (Boolean.getBoolean("colorlight.forceDistantHorizons"))
            return true;

        return dh.get().getMetadata().getVersion().getFriendlyString().startsWith(SUPPORTED_DH_PREFIX);
    }
}
