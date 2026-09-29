package me.mrhikmen.colorlight.client;

import me.mrhikmen.colorlight.client.core.scanner.BlockScanner;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public class ReloadListener implements SimpleSynchronousResourceReloadListener {

    @Override
    public Identifier getFabricId() {
        return Identifier.fromNamespaceAndPath("colorlight", "texture_scanner");
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        ColorLightClient.config.load();

        BlockScanner.discoverNewBlocks();

        ColorLightClient.config.save();
        ColorLightBlockRegistry.load(ColorLightClient.config);
        ColorLightClient.LOGGER.info("[ColorLight] Textures initialized");
    }
}