package me.mrhikmen.colorlight.client.compat.distanthorizons;

import net.fabricmc.loader.api.FabricLoader;

public final class ColorLightDistantHorizonsCompat {

    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("distanthorizons");

    public static boolean isPresent() {
        return PRESENT;
    }

    private ColorLightDistantHorizonsCompat() {
    }
}
