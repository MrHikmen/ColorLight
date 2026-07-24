package me.mrhikmen.colorlight;

import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.ReloadListener;
import me.mrhikmen.colorlight.light.ColorLightEngineHolder;
import me.mrhikmen.colorlight.light.ColorLightTestCommand;
import me.mrhikmen.colorlight.render.ColorLightTestModelPlugin;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;

import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ColorLightClient implements ClientModInitializer {

    public static String ModID = "ColorLight";

    public static final Logger LOGGER = LoggerFactory.getLogger(ModID);

    @Override
    public void onInitializeClient() {
        ModelLoadingPlugin.register(new ColorLightTestModelPlugin());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                ColorLightEngineHolder.set(client.level));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                ColorLightEngineHolder.set(null));

        ColorLightTestCommand.register();

        ColorLightClient.LOGGER.info("Mod is loading");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new ReloadListener());
    }
}
