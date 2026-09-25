package me.mrhikmen.colorlight.client.core.light.registry;

import me.mrhikmen.colorlight.api.block.BlockLightDefinition;
import me.mrhikmen.colorlight.api.block.ColorLightBlockAPI;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

public final class ColorLightBlockRegistry {

    private static volatile Map<Block, BlockSettings> byBlock = new HashMap<>();

    public static void load(ColorLightConfig config) {
        Map<Block, BlockSettings> map = new HashMap<>();

        for (BlockLightDefinition definition : ColorLightBlockAPI.snapshot()) {
            try {
                Block block = BuiltInRegistries.BLOCK.getValue(definition.block());
                if (block != null) {
                    map.put(block, definition.toBlockSettings());
                }
            } catch (Exception e) {
            }
        }

        for (BlockSettings entry : config.blocks) {
            if (!entry.enable)
                continue;

            if (entry.light <= 0)
                continue;

            if (entry.r == 0 && entry.g == 0 && entry.b == 0)
                continue;

            try {
                Block block = BuiltInRegistries.BLOCK.getValue(entry.getBlock());
                if (block == null)
                    continue;

                if (entry.edit || !map.containsKey(block)) {
                    map.put(block, entry);
                }
            } catch (Exception e) {
            }
        }
        byBlock = map;
    }

    public static BlockSettings get(Block block) {
        return byBlock.get(block);
    }

    public static boolean isEmpty() {
        return byBlock.isEmpty();
    }

    private ColorLightBlockRegistry() {
    }
}