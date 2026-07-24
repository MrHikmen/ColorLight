package me.mrhikmen.colorlight.config;

import net.minecraft.resources.ResourceLocation;

public class ColorLightSaveBlock {
    public String block;
    public int r;
    public int g;
    public int b;
    public int light;

    public ColorLightSaveBlock() {
        // нужен Gson
    }

    public ColorLightSaveBlock(ResourceLocation block, int light) {
        this.block = String.valueOf(block);
        this.light = light;
    }
    public ColorLightSaveBlock(int r, int g, int b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }
    public ResourceLocation getBlock() {
        return ResourceLocation.parse(block);
    }
    public Object[] toArray() {
        return new Object[] { block, r, g, b, light };
    }
}
