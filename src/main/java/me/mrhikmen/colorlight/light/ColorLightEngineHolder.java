package me.mrhikmen.colorlight.light;

import net.minecraft.client.multiplayer.ClientLevel;

public final class ColorLightEngineHolder {

    private static ColorLightEngine engine;

    public static void set(ClientLevel level) {
        engine = (level != null) ? new ColorLightEngine(level) : null;
    }

    public static ColorLightEngine get() {
        return engine;
    }

    private ColorLightEngineHolder() {
    }
}
