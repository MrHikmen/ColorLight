package me.mrhikmen.colorlight.core.scanner;

import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.Save;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class LightBlock {
    public LightBlock(ColorLightConfig config) {
        int i = 0;
        for (Block block : BuiltInRegistries.BLOCK) {

            int maxLight = 0;

            for (BlockState nowstate : block.getStateDefinition().getPossibleStates()) {

                if (nowstate.getLightEmission() > maxLight) {
                    maxLight = nowstate.getLightEmission();
                }

            }
            if (maxLight > 0) {

                new Save(config, BuiltInRegistries.BLOCK.getKey(block), maxLight);

            }
        }
    }
}
