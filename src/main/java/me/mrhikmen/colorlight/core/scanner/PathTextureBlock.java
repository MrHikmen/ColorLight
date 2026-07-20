package me.mrhikmen.colorlight.core.scanner;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.Save;
import me.mrhikmen.colorlight.core.scanner.blockstateresolver.*;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.*;
import java.nio.charset.StandardCharsets;

import java.util.Map;
import java.util.Optional;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PathTextureBlock {
    public void ConverterBlock() {
        for (int i = 0; i < ColorLightConfig.i;) {
            String modid = ColorLightConfig.blocklist.get(i).substring(0, ColorLightConfig.blocklist.get(i).indexOf(':'));
            String blockid = ColorLightConfig.blocklist.get(i).substring(ColorLightConfig.blocklist.get(i).indexOf(':') + 1);
            new Save(modid, blockid);
            ColorLightClient.LOGGER.info("Mod: " + modid + " Block: " + blockid + " Счет: " + i);
            new PathTextureBlock(modid, blockid, i, ColorLightConfig.State.get(i));
            i++;
        }
    }
    public PathTextureBlock(){
    }
    PathTextureBlock(String modid, String blockid, int i, BlockState state) {
        ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(modid, "blockstates/" + blockid + ".json");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(modelId);
        if(resource.isPresent()) {
            try (InputStream stream = resource.get().open()) {

                JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

                if (json.has("variants")) {
                    new VariantParser(state, json);
                } else if (json.has("multipart")) {
                    new MultipartParser();
                } else {
                    ColorLightClient.LOGGER.info("Model not found");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            ResourceLocation textureId = ResourceLocation.parse(modid + ":block/" + blockid);
            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(), "textures/" + textureId.getPath() + ".png");
            PixelData best = SearchBestPixel.search(texture);
            ColorLightClient.LOGGER.info(best.r + " " + best.g + " " + best.b + " score = " + best.score);
        }
    }
    private static String propertyValue(Property<?> property, Comparable<?> value) {
        return ((Property) property).getName(value);
    }
    public static String stateToVariant(BlockState state) {

        StringBuilder builder = new StringBuilder();

        boolean first = true;

        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {

            if (!first) {
                builder.append(",");
            }

            first = false;

            builder.append(entry.getKey().getName());
            builder.append("=");
            builder.append(propertyValue(entry.getKey(), entry.getValue()));
        }

        return builder.toString();
    }
}