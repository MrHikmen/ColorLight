package me.mrhikmen.colorlight;

import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.ReloadListener;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;

import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ColorLightClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ColorLight");

    public String ModID = "ColorLight";

    @Override
    public void onInitializeClient() {
        ColorLightConfig config = new ColorLightConfig();
        config.load();



        ColorLightClient.LOGGER.info("Mod is loading");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new ReloadListener());

    }
}
