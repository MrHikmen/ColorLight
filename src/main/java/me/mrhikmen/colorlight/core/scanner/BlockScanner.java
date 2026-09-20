package me.mrhikmen.colorlight.core.scanner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.BlockSettings;
import me.mrhikmen.colorlight.core.scanner.model.ModelTextureResolver;
import me.mrhikmen.colorlight.core.scanner.model.blockstate.MultipartParser;
import me.mrhikmen.colorlight.core.scanner.model.blockstate.VariantParser;
import me.mrhikmen.colorlight.core.scanner.texture.PixelData;
import me.mrhikmen.colorlight.core.scanner.texture.SearchBestPixel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class BlockScanner {

    public static void discoverNewBlocks() {
        // texture/model results are only valid for one set of resources and scoring weights
        SearchBestPixel.clearCache();
        ModelTextureResolver.clearCache();

        // one pass to index the config (it used to be scanned linearly, twice, for every block in the game)
        Map<Identifier, BlockSettings> known = new HashMap<>();
        for (BlockSettings entry : ColorLightClient.config.blocks) {
            known.putIfAbsent(entry.getBlock(), entry); // first entry wins, like the old linear search
        }

        for (Block block : BuiltInRegistries.BLOCK) {
            int maxLight = maxLightEmission(block);
            if (maxLight <= 0)
                continue;

            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            BlockSettings existing = known.get(id);

            if (existing != null) {
                if (!existing.edit) {
                    scanColorFor(existing);
                }
                continue;
            }

            BlockSettings created = new BlockSettings(id, maxLight, true);
            ColorLightClient.config.blocks.add(created);
            known.put(id, created);
            scanColorFor(created);
        }
    }

    public static void resetToDefault(BlockSettings entry) {
        Identifier id = entry.getBlock();
        Block block = BuiltInRegistries.BLOCK.getValue(id);

        int maxLight = block == null ? 0 : maxLightEmission(block);

        entry.enable = true;
        entry.light = Math.max(1, maxLight);
        entry.r = 255;
        entry.g = 255;
        entry.b = 255;
        entry.edit = false;

        // the scoring weights may have been changed in the GUI since the last scan
        SearchBestPixel.clearCache();

        if (ColorLightClient.config.blocks.contains(entry))
            scanColorFor(entry);
    }

    private static int maxLightEmission(Block block) {
        int maxLight = 0;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (state.getLightEmission() > maxLight)
                maxLight = state.getLightEmission();
        }
        return maxLight;
    }

    private static void scanColorFor(BlockSettings entry) {
        Identifier block = entry.getBlock();

        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(
                Identifier.fromNamespaceAndPath(block.getNamespace(), "blockstates/" + block.getPath() + ".json")
        );

            if (resource.isPresent()) {
                try (InputStream stream = resource.get().open()) {
                    JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

                    if (json.has("variants")) {
                        new VariantParser(json, entry);
                    } else if (json.has("multipart")) {
                        new MultipartParser(json, entry);
                    } else {
                        ColorLightClient.LOGGER.info("[ColorLight] Model not found; name: {}", block.getPath());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                Identifier texture = Identifier.fromNamespaceAndPath(block.getNamespace(), "block/" + block.getPath());

                PixelData best = SearchBestPixel.search(texture);

                if (best != null) {
                    entry.r = best.r;
                    entry.g = best.g;
                    entry.b = best.b;
                }
            }
    }

    private BlockScanner() {
    }
}
