package me.mrhikmen.colorlight.compat.lod.voxy;

import net.fabricmc.loader.api.FabricLoader;

public final class ColorLightVoxyCompat {

    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("voxy");

    public static boolean isPresent() {
        return PRESENT;
    }

    private ColorLightVoxyCompat() {
    }
}
