package me.mrhikmen.colorlight.core.scanner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.BlockSettings;
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
import java.util.List;
import java.util.Optional;

public final class BlockScanner {

    public static void discoverNewBlocks() {
        for (Block block : BuiltInRegistries.BLOCK) {
            int maxLight = maxLightEmission(block);
            if (maxLight <= 0)
                continue;

            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (findIndex(id) >= 0)
                continue;

            ColorLightClient.config.blocks.add(new BlockSettings(id, maxLight, true));
            scanColorFor(ColorLightClient.config.blocks.size() - 1);
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

        int index = findIndex(id);
        if (index >= 0)
            scanColorFor(index);
    }

    private static int maxLightEmission(Block block) {
        int maxLight = 0;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (state.getLightEmission() > maxLight)
                maxLight = state.getLightEmission();
        }
        return maxLight;
    }

    private static int findIndex(Identifier id) {
        List<BlockSettings> blocks = ColorLightClient.config.blocks;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).getBlock().equals(id))
                return i;
        }
        return -1;
    }

    private static void scanColorFor(int index) {
        Identifier block = ColorLightClient.config.blocks.get(index).getBlock();

        Identifier modelId = Identifier.fromNamespaceAndPath(block.getNamespace(), "blockstates/" + block.getPath() + ".json");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(modelId);

        if (resource.isPresent()) {
            try (InputStream stream = resource.get().open()) {
                JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

                if (json.has("variants")) {
                    new VariantParser(json, index);
                } else if (json.has("multipart")) {
                    new MultipartParser(json, index);
                } else {
                    ColorLightClient.LOGGER.info("[ColorLight] Model not found");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            Identifier texture = Identifier.fromNamespaceAndPath(block.getNamespace(), "block/" + block.getPath());

            PixelData best = SearchBestPixel.search(texture);

            if (best != null) {
                BlockSettings data = ColorLightClient.config.blocks.get(index);
                data.r = best.r;
                data.g = best.g;
                data.b = best.b;
            }
        }
    }

    private BlockScanner() {
    }
}
