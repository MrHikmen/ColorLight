package me.mrhikmen.colorlight.core.scanner;

import me.mrhikmen.colorlight.core.Save;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class LightBlock {
    public LightBlock() {
        int i = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = null;
            int maxLight = 0;

            for (BlockState nowstate : block.getStateDefinition().getPossibleStates()) {
                if (nowstate.getLightEmission() > maxLight) {
                    maxLight = nowstate.getLightEmission();
                    state = nowstate;
                }
            }
            if (state != null && maxLight > 0) {
                new Save(BuiltInRegistries.BLOCK.getKey(block), maxLight, i++, state);
            }else if (state == null && maxLight > 0) {
                new Save(BuiltInRegistries.BLOCK.getKey(block), maxLight, i++);
            }
        }
    }
}
