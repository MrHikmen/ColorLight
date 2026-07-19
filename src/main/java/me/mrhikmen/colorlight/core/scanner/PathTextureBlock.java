package me.mrhikmen.colorlight.core.scanner;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.Save;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.*;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static me.mrhikmen.colorlight.core.scanner.ScanTextureBlock.*;

public class PathTextureBlock {
    public void ConverterBlock() {
        for (int i = 0; i < ColorLightConfig.i; i++) {
            String modid = ColorLightConfig.blocklist.get(i).substring(0, ColorLightConfig.blocklist.get(i).indexOf(':'));
            String blockid = ColorLightConfig.blocklist.get(i).substring(ColorLightConfig.blocklist.get(i).indexOf(':') + 1);
            new Save(modid, blockid);
            new SearchScanTexture(modid, blockid, i);
            ColorLightClient.LOGGER.info("Mod: " + modid + " Block: " + blockid + " Счет: " + i);
        }
    }

    class SearchScanTexture {
        SearchScanTexture(String modid, String blockid, int i) {
            ResourceLocation textureId = ResourceLocation.parse(modid + ":block/" + blockid);
            boolean exists = Minecraft.getInstance().getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(), "textures/" + textureId.getPath() + ".png")).isPresent();
            if(exists){
                PixelData best = SearchBestPixel.search(textureId);
                ColorLightClient.LOGGER.info(best.r + " " + best.g + " " + best.b + " score = " + best.score);
            }
            else{
                ResourceLocation modelId = ResourceLocation.parse(modid + ":block/" + blockid);
                Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json"));

                if (resource.isPresent()) {
                    try (InputStream stream = resource.get().open()) {

                        JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

                        if (json.has("textures")) {

                            JsonObject textures = json.getAsJsonObject("textures");

                            List<PixelData> bestPixels = new ArrayList<>();
                            PixelData best = null;

                            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                                ResourceLocation texture = ResourceLocation.parse(entry.getValue().getAsString());
                                bestPixels.add(SearchBestPixel.search(texture));
                                for (PixelData pixel : bestPixels) {

                                    if (best == null || pixel.score > best.score)
                                        best = pixel;

                                }
                            }
                            ColorLightClient.LOGGER.info(best.r + " " + best.g + " " + best.b + " score = " + best.score);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }
}
