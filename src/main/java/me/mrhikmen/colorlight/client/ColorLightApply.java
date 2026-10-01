package me.mrhikmen.colorlight.client;

import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.registry.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.client.core.light.scan.ColorLightChunkScanner;

import net.minecraft.client.Minecraft;

/**
 * Puts the current settings, scripts and block rules into effect. Used after a resource reload and after the player
 * changes a setting - both need the same four steps.
 */
public final class ColorLightApply {

    /**
     * @param saveConfig write the config file first (the GUI wants this; the reload listener already saved)
     */
    public static void everything(boolean saveConfig) {
        ColorLightConfig config = ColorLightClient.config;
        if (saveConfig)
            config.save();

        ColorLightEngineHolder.configure(config.lightRangeBlocks, config.PROPAGATION_MODE, config.DYNAMIC_PROPAGATION);
        ColorLightBlockRegistry.load(config);

        Minecraft client = Minecraft.getInstance();
        if (client.level == null)
            return;

        ColorLightEngine oldEngine = ColorLightEngineHolder.get();
        ColorLightEngineHolder.set(client.level);
        ColorLightEngine newEngine = ColorLightEngineHolder.get();
        // the old engine's light is gone: rebuild exactly the sections it had lit (no whole-render-distance rebuild)
        if (newEngine != null && oldEngine != null)
            newEngine.inheritDirtyFrom(oldEngine);
        if (config.ENABLE)
            ColorLightChunkScanner.rescanAll(client.level);
    }

    private ColorLightApply() {
    }
}
