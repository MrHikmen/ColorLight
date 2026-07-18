package me.mrhikmen.colorlight.core;

import me.mrhikmen.colorlight.ColorLightClient;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;

public class Manager {

    public Manager() {
        ColorLightClient.LOGGER.info("Mod is loading");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new ReloadListener());
    }
}
