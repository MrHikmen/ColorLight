package me.mrhikmen.colorlight.core.scanner.model;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.config.ColorLightSaveBlock;
import me.mrhikmen.colorlight.core.scanner.model.blockstate.*;
import me.mrhikmen.colorlight.core.scanner.texture.PixelData;
import me.mrhikmen.colorlight.core.scanner.texture.SearchBestPixel;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PathTextureBlock {

    public PathTextureBlock(ColorLightConfig config) {

        for (int i = 0; i < config.blocks.size();) {

            ResourceLocation block = config.blocks.get(i).getBlock();;

            ColorLightClient.LOGGER.info("Mod: " + block.getNamespace()  + " Block: " + block.getPath() + " Счет: " + i);

            ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(block.getNamespace(), "blockstates/" + block.getPath() + ".json");
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(modelId);

            if (resource.isPresent()) {

                try (InputStream stream = resource.get().open()) {

                    JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

                    if (json.has("variants")) {
                        new VariantParser(json, i, config);
                    } else if (json.has("multipart")) {
                        new MultipartParser(json, i, config);
                    } else {
                        ColorLightClient.LOGGER.info("Model not found");
                    }

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {

                ResourceLocation textureId = ResourceLocation.parse(block.getNamespace() + ":block/" + block.getPath());
                ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(), "textures/" + textureId.getPath() + ".png");

                PixelData best = SearchBestPixel.search(texture);

                ColorLightSaveBlock data = config.blocks.get(i);

                data.r = best.r;
                data.g = best.g;
                data.b = best.b;

                ColorLightClient.LOGGER.info(best.r + " " + best.g + " " + best.b + " score = " + best.score);
            }

            i++;
        }
    }
}