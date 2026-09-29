package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.core.light.propagation.ColorLightPropagationMode;

import net.minecraft.client.multiplayer.ClientLevel;

public final class ColorLightEngineHolder {

    private static volatile ColorLightEngine engine;

    private static int maxRangeBlocks = 15;
    private static ColorLightPropagationMode propagationMode = ColorLightPropagationMode.GRID;

    public static void configure(int maxRangeBlocks) {
        configure(maxRangeBlocks, propagationMode);
    }

    public static void configure(int maxRangeBlocks, ColorLightPropagationMode mode) {
        ColorLightEngineHolder.maxRangeBlocks = Math.max(1, maxRangeBlocks);
        ColorLightEngineHolder.propagationMode = (mode != null) ? mode : ColorLightPropagationMode.GRID;
    }

    public static void set(ClientLevel level) {
        if (level == null) {
            engine = null;
            return;
        }
        engine = new ColorLightEngine(level, maxRangeBlocks, propagationMode);
    }

    public static ColorLightEngine get() {
        return engine;
    }

    public static int getMaxRangeBlocks() {
        return maxRangeBlocks;
    }

    public static ColorLightPropagationMode getPropagationMode() {
        return propagationMode;
    }

    public static void tick() {

    }
}
