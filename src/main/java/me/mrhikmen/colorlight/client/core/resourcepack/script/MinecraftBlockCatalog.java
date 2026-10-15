package me.mrhikmen.colorlight.client.core.resourcepack.script;

import me.mrhikmen.colorlight.client.core.resourcepack.data.BlockCatalog;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link BlockCatalog} backed by the game's block registry. */
public final class MinecraftBlockCatalog implements BlockCatalog {

    private final Map<String, Block> byId = new HashMap<>();
    private final List<String> ids = new ArrayList<>();
    private final Map<String, TagKey<Block>> tags = new HashMap<>();

    public MinecraftBlockCatalog() {
        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            byId.put(id, block);
            ids.add(id);
        }
    }

    @Override
    public boolean exists(String blockId) {
        return byId.containsKey(blockId);
    }

    @Override
    public Iterable<String> allIds() {
        return ids;
    }

    @Override
    public boolean inTag(String blockId, String tagId) {
        Block block = byId.get(blockId);
        if (block == null)
            return false;
        try {
            TagKey<Block> tag = tags.computeIfAbsent(tagId, t -> TagKey.create(Registries.BLOCK, Identifier.parse(t)));
            return block.defaultBlockState().is(tag);
        } catch (Exception e) {
            return false; // malformed tag id in a script
        }
    }

    @Override
    public boolean isModLoaded(String modId) {
        return "minecraft".equals(modId) || FabricLoader.getInstance().isModLoaded(modId);
    }
}
