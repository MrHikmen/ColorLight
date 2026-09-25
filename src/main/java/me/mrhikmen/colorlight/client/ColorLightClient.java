package me.mrhikmen.colorlight.client;

import me.mrhikmen.colorlight.api.propagation.builtin.BuiltinPropagationMethods;
import me.mrhikmen.colorlight.client.compat.lambdynlights.ColorLightEntityLightTicker;
import me.mrhikmen.colorlight.client.compat.lambdynlights.ColorLightLambDynLightsCompat;
import me.mrhikmen.colorlight.client.compat.lod.LodColorLightCompat;
import me.mrhikmen.colorlight.client.compat.lod.voxy.ColorLightVoxyCompat;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.client.core.light.scan.ColorLightChunkScanner;
import me.mrhikmen.colorlight.client.core.light.runtime.ColorLightDaylightRefresher;
import me.mrhikmen.colorlight.client.core.light.runtime.ColorLightDirtyFlusher;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.propagation.ColorLightPropagationMode;
import me.mrhikmen.colorlight.client.core.light.command.ColorLightCommand;
import me.mrhikmen.colorlight.client.core.render.ModelPlugin;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;

import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ColorLightClient implements ClientModInitializer {

    public static String ModID = "ColorLight";

    public static final Logger LOGGER = LoggerFactory.getLogger(ModID);

    public static ColorLightConfig config = new ColorLightConfig();

    @Override
    public void onInitializeClient() {
        // Registers ColorLight's own propagation methods (grid, smooth) through the same public API a
        // third-party mod would use (see me.mrhikmen.colorlight.api.propagation). Must happen before
        // anything resolves a propagation id, e.g. the config load and block registry below, and before
        // any other mod's own init runs so its methods register right after these.
        BuiltinPropagationMethods.registerAll();

        config.load();

        ColorLightEngineHolder.configure(config.lightRangeBlocks, ColorLightPropagationMode.fromConfigString(config.PROPAGATION_MODE));
        ColorLightBlockRegistry.load(config);
        ColorLightDaylightRefresher.register();
        ColorLightDirtyFlusher.register();

        ModelLoadingPlugin.register(new ModelPlugin());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ColorLightEngineHolder.set(client.level));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ColorLightEngineHolder.set(null));

        ClientTickEvents.END_CLIENT_TICK.register(client -> ColorLightEngineHolder.tick());

        ColorLightChunkScanner.register();
        ColorLightCommand.register();

        if (ColorLightVoxyCompat.isPresent()) {
            LodColorLightCompat.register();
            ColorLightClient.LOGGER.info("[ColorLight] Voxy initialized");
        }

        if (ColorLightLambDynLightsCompat.isPresent()) {
            ColorLightEntityLightTicker.register();
            ColorLightClient.LOGGER.info("[ColorLight] LambDynamicLights initialized");
        }

        ColorLightClient.LOGGER.info("[ColorLight] Mod initialized");

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new ReloadListener());
    }
}