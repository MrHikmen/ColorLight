package me.mrhikmen.colorlight.client;

import me.mrhikmen.colorlight.client.compat.lambdynlights.ColorLightEntityLightTicker;
import me.mrhikmen.colorlight.client.compat.lambdynlights.ColorLightLambDynLightsCompat;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.client.core.light.scan.ColorLightChunkScanner;
import me.mrhikmen.colorlight.client.core.light.runtime.ColorLightDaylightRefresher;
import me.mrhikmen.colorlight.client.core.light.runtime.ColorLightDirtyFlusher;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.script.ScriptRuntime;
import me.mrhikmen.colorlight.client.core.light.command.ColorLightCommand;
import me.mrhikmen.colorlight.client.core.render.ModelPlugin;
import me.mrhikmen.colorlight.client.gpu.ColorLightGpu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ColorLightClient implements ClientModInitializer {

    public static String ModID = "ColorLight";

    public static final Logger LOGGER = LoggerFactory.getLogger(ModID);

    public static ColorLightConfig config = new ColorLightConfig();

    @Override
    public void onInitializeClient() {
        // The propagation methods themselves come from Lua files in resource packs (assets/colorlight/propagation).
        // Until the first resource reload has run them, the two Java fallbacks keep "grid" and "smooth" resolvable.
        ScriptRuntime.registerFallbacks();

        config.load();

        ColorLightEngineHolder.configure(config.lightRangeBlocks, config.PROPAGATION_MODE, config.DYNAMIC_PROPAGATION);
        ColorLightBlockRegistry.load(config);
        ColorLightDaylightRefresher.register();
        ColorLightDirtyFlusher.register();
        ClientTickEvents.END_CLIENT_TICK.register(ColorLightGpu::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ColorLightGpu.shutdown());

        ModelLoadingPlugin.register(new ModelPlugin());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ColorLightEngineHolder.set(client.level));
        // Block tags (used by colorlight.tag / "#minecraft:candles" in block scripts) only exist once the server has
        // sent them, which is after the resource reload - so rebuild the block rules when they arrive.
        CommonLifecycleEvents.TAGS_LOADED.register((registries, isClient) -> {
            if (isClient)
                ColorLightBlockRegistry.load(config);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ColorLightEngineHolder.set(null));

        ClientTickEvents.END_CLIENT_TICK.register(client -> ColorLightEngineHolder.tick());

        ColorLightChunkScanner.register();
        ColorLightCommand.register();

        if (ColorLightLambDynLightsCompat.isPresent()) {
            ColorLightEntityLightTicker.register();
            ColorLightClient.LOGGER.info("[ColorLight] LambDynamicLights initialized");
        }

        ColorLightClient.LOGGER.info("[ColorLight] Mod initialized");

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new ReloadListener());
    }
}