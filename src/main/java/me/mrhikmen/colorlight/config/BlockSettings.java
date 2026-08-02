package me.mrhikmen.colorlight.config;

import net.minecraft.resources.Identifier;

public class BlockSettings {
    public String block;
    public int r;
    public int g;
    public int b;
    public int light;

    public BlockSettings() {
    }

    public BlockSettings(Identifier block, int light) {
        this.block = String.valueOf(block);
        this.light = light;
    }

    public Identifier getBlock() {
        return Identifier.parse(block);
    }
}
