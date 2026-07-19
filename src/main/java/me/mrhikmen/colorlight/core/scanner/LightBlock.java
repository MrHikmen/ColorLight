package me.mrhikmen.colorlight.core.scanner;

import me.mrhikmen.colorlight.core.Save;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class LightBlock {
    public LightBlock() {
        int i = 0;
        for (Block block : BuiltInRegistries.BLOCK) {

            int maxLight = 0;

            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                maxLight = Math.max(maxLight, state.getLightEmission());
            }

            if (maxLight > 0) {
                new Save(
                        BuiltInRegistries.BLOCK.getKey(block),
                        maxLight,
                        i++
                );
            }
        }
    }
    //Опеделяет, какие блоки должны светиться
}
