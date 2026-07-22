package me.mrhikmen.colorlight.core;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.scanner.LightBlock;
import me.mrhikmen.colorlight.core.scanner.model.PathTextureBlock;

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

public class ReloadListener implements SimpleSynchronousResourceReloadListener {

    @Override
    public ResourceLocation getFabricId() {
        return ResourceLocation.fromNamespaceAndPath(
                "colorlight",
                "texture_scanner"
        );
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        ColorLightConfig config = new ColorLightConfig();

        config.load();
        new LightBlock(config);
        new PathTextureBlock(config);
        ColorLightClient.LOGGER.info("ColorLight RP is loaded");
    }
}
