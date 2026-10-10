package me.mrhikmen.colorlight.client.core.light.registry;

import me.mrhikmen.colorlight.api.block.BlockLightDefinition;
import me.mrhikmen.colorlight.api.block.ColorLightBlockAPI;
import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.config.ColorLightConfig;
import me.mrhikmen.colorlight.client.core.resourcepack.script.DataRuntime;
import me.mrhikmen.colorlight.client.core.resourcepack.data.BlockOverride;
import me.mrhikmen.colorlight.client.core.resourcepack.data.BlockRules;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * The final answer to "does this block glow, in which colour, and how does its light spread".
 * <p>
 * Sources are layered, lowest priority first:
 * <ol>
 *     <li>the texture scan (entries in the config that the player did not touch);</li>
 *     <li>defaults other mods registered through {@link ColorLightBlockAPI};</li>
 *     <li>resource-pack block files ({@code assets/colorlight/block/*.json}), field by field;</li>
 *     <li>entries the player edited by hand in the GUI ({@code edit = true}) - these always win.</li>
 * </ol>
 */
public final class ColorLightBlockRegistry {

    private static volatile Map<Block, BlockSettings> byBlock = new HashMap<>();

    public static void load(ColorLightConfig config) {
        Map<Block, BlockSettings> map = new HashMap<>();

        // 1. texture scan
        for (BlockSettings entry : config.blocks) {
            if (entry.edit)
                continue;
            Block block = blockOf(entry);
            if (block != null)
                map.put(block, entry.copy());
        }

        // 2. other mods' defaults
        for (BlockLightDefinition definition : ColorLightBlockAPI.snapshot()) {
            try {
                Block block = BuiltInRegistries.BLOCK.getValue(definition.block());
                if (block != null)
                    map.put(block, definition.toBlockSettings());
            } catch (Exception e) {
                // an id that does not name a block
            }
        }

        // 3. resource-pack block files
        BlockRules rules = DataRuntime.blockRules();
        for (Map.Entry<String, BlockOverride> rule : rules.resolve(DataRuntime.catalog()).entrySet()) {
            Block block = blockById(rule.getKey());
            if (block == null)
                continue;
            BlockOverride o = rule.getValue();

            if (Boolean.FALSE.equals(o.enabled)) {
                map.remove(block);
                continue;
            }

            BlockSettings base = map.get(block);
            if (base == null) {
                if (!o.hasColor())
                    continue; // nothing known about this block's colour: the rule alone cannot light it
                base = new BlockSettings(BuiltInRegistries.BLOCK.getKey(block), 15, true);
                map.put(block, base);
            }
            if (o.hasColor()) {
                base.r = o.r;
                base.g = o.g;
                base.b = o.b;
            }
            if (o.light != null)
                base.light = o.light;
            if (o.propagation != null)
                base.propagation = o.propagation;
            base.enable = true;
        }

        // 4. the player's own entries
        for (BlockSettings entry : config.blocks) {
            if (!entry.edit)
                continue;
            Block block = blockOf(entry);
            if (block == null)
                continue;
            if (!entry.enable) {
                map.remove(block); // switched off by hand
                continue;
            }
            map.put(block, entry);
        }

        // drop what cannot glow
        map.values().removeIf(s -> !s.enable || s.light <= 0 || (s.r == 0 && s.g == 0 && s.b == 0));

        byBlock = map;
    }

    private static Block blockOf(BlockSettings entry) {
        try {
            return BuiltInRegistries.BLOCK.getValue(entry.getBlock());
        } catch (Exception e) {
            return null;
        }
    }

    private static Block blockById(String id) {
        try {
            return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        } catch (Exception e) {
            ColorLightClient.LOGGER.warn("[ColorLight] script names an invalid block id '{}'", id);
            return null;
        }
    }

    public static BlockSettings get(Block block) {
        return byBlock.get(block);
    }

    /** Every glowing block with its final settings (what the scanner and compat layers use). */
    public static Map<Block, BlockSettings> entries() {
        return byBlock;
    }

    public static boolean isEmpty() {
        return byBlock.isEmpty();
    }

    private ColorLightBlockRegistry() {
    }
}
