package me.mrhikmen.colorlight.core;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.core.scanner.LightBlock;
import me.mrhikmen.colorlight.core.scanner.PathTextureBlock;

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
        new LightBlock();
        new PathTextureBlock().ConverterBlock();
        ColorLightClient.LOGGER.info("ColorLight RP is loaded");
    }
}
