package me.mrhikmen.colorlight;

import me.mrhikmen.colorlight.core.scanner.LightBlock;
import me.mrhikmen.colorlight.core.scanner.model.PathTextureBlock;
import me.mrhikmen.colorlight.light.*;

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

public class ReloadListener implements SimpleSynchronousResourceReloadListener {

    @Override
    public ResourceLocation getFabricId() {
        return ResourceLocation.fromNamespaceAndPath("colorlight", "texture_scanner");
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        ColorLightClient.config.load();
        ColorLightClient.config.blocks.clear();

        new LightBlock(ColorLightClient.config);
        new PathTextureBlock(ColorLightClient.config);

        ColorLightClient.config.save();
        ColorLightBlockRegistry.load(ColorLightClient.config);
        ColorLightClient.LOGGER.info("ColorLight RP is loaded");
    }
}