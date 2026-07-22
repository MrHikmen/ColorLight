package me.mrhikmen.colorlight.core;

import me.mrhikmen.colorlight.config.ColorLightConfig;

import net.minecraft.resources.ResourceLocation;

public class Save {
    public Save(ColorLightConfig config, ResourceLocation block, int light){
        config.blocklist.add(block);
        config.light.add(light);
    }

    public Save(int rgb) {
    }
}
