package me.mrhikmen.colorlight.core.scanner;

import me.mrhikmen.colorlight.core.Save;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class LightBlock {
    public LightBlock() {
        int i = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (state.getLightEmission() > 0) {
                new Save(BuiltInRegistries.BLOCK.getKey(block), state.getLightEmission(), i);
                i++;
            }
        }
    }
    //Опеделяет, какие блоки должны светиться
}
