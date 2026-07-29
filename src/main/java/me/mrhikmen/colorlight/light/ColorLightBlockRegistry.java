package me.mrhikmen.colorlight.light;

import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.config.ColorLightSaveBlock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

public final class ColorLightBlockRegistry {

    private static Map<Block, ColorLightSaveBlock> byBlock = new HashMap<>();

    public static void load(ColorLightConfig config) {
        Map<Block, ColorLightSaveBlock> map = new HashMap<>();

        for (ColorLightSaveBlock entry : config.blocks) {

            if (entry.light <= 0)
                continue;

            if (entry.r == 0 && entry.g == 0 && entry.b == 0)
                continue;

            try {
                Block block = BuiltInRegistries.BLOCK.get(entry.getBlock());
                map.put(block, entry);
            } catch (Exception e) {
            }
        }

        byBlock = map;
    }

    public static ColorLightSaveBlock get(Block block) {
        return byBlock.get(block);
    }

    public static boolean isEmpty() {
        return byBlock.isEmpty();
    }

    private ColorLightBlockRegistry() {
    }
}