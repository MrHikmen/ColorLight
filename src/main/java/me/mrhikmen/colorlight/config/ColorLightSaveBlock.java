package me.mrhikmen.colorlight.config;

import net.minecraft.resources.ResourceLocation;

public class ColorLightSaveBlock {
    public String block;
    public int r;
    public int g;
    public int b;
    public int light;

    public ColorLightSaveBlock() {
    }

    public ColorLightSaveBlock(ResourceLocation block, int light) {
        this.block = String.valueOf(block);
        this.light = light;
    }

    public ResourceLocation getBlock() {
        return ResourceLocation.parse(block);
    }
}
