package me.mrhikmen.colorlight.client;

import me.mrhikmen.colorlight.client.core.resourcepack.scanner.BlockScanner;
import me.mrhikmen.colorlight.client.core.resourcepack.script.ScriptRuntime;

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
        // 1. the packs' Lua scripts: propagation methods, default settings, block rules
        ScriptRuntime.reload(manager);

        // 2. the player's config, with the packs' defaults laid under it (the scanner weights below depend on them)
        ColorLightClient.config.load();
        ColorLightClient.config.setPackDefaults(ScriptRuntime.settings());

        // 3. colours of glowing blocks from their textures
        BlockScanner.discoverNewBlocks();
        ColorLightClient.config.save();

        // 4. block rules + engine (+ relight the loaded world, if any)
        ColorLightApply.everything(false);

        ColorLightClient.LOGGER.info("[ColorLight] Textures and Lua scripts initialized");
    }
}
