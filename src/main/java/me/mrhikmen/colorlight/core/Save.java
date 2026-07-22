package me.mrhikmen.colorlight.core;

import me.mrhikmen.colorlight.config.ColorLightConfig;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public class Save {
    public Save(ResourceLocation block, int light, int i){
        ColorLightConfig.blocklist.add(block);
        ColorLightConfig.light.add(light);
        ColorLightConfig.i = i;
        ColorLightConfig.mod_id.add(block.getNamespace());
        ColorLightConfig.block_id.add(block.getPath());
    }

    public Save(ResourceLocation block, int light, int i, BlockState State){
        ColorLightConfig.blocklist.add(block);
        ColorLightConfig.light.add(light);
        ColorLightConfig.i = i;
        ColorLightConfig.State.add(State);
        ColorLightConfig.mod_id.add(block.getNamespace());
        ColorLightConfig.block_id.add(block.getPath());
    }

    public Save(int rgb) {
    }
}
