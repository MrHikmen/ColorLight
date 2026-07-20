package me.mrhikmen.colorlight.core;

import me.mrhikmen.colorlight.config.ColorLightConfig;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public class Save {
    public Save(ResourceLocation block, int light, int i){
        ColorLightConfig.blocklist.add(String.valueOf(block));
        ColorLightConfig.light.add(light);
        ColorLightConfig.i = i;
    }

    public Save(ResourceLocation block, int light, int i, BlockState State){
        ColorLightConfig.blocklist.add(String.valueOf(block));
        ColorLightConfig.light.add(light);
        ColorLightConfig.i = i;
        ColorLightConfig.State.add(State);
    }


    public Save(String modid, String blockid){
        ColorLightConfig.mod_id.add(modid);
        ColorLightConfig.block_id.add(blockid);
    }

    public Save(int rgb) {
    }
}
