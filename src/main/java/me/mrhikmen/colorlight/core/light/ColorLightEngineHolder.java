package me.mrhikmen.colorlight.core.light;

import net.minecraft.client.multiplayer.ClientLevel;

public final class ColorLightEngineHolder {

    private static ColorLightEngine engine;

    private static int maxRangeBlocks = 15;

    public static void configure(int maxRangeBlocks) {
        ColorLightEngineHolder.maxRangeBlocks = Math.max(1, maxRangeBlocks);
    }

    public static void set(ClientLevel level) {
        engine = (level != null) ? new ColorLightEngine(level, maxRangeBlocks) : null;
    }

    public static ColorLightEngine get() {
        return engine;
    }

    private ColorLightEngineHolder() {
    }
}