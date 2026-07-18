package me.mrhikmen.colorlight.core.scanner;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.Save;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TextureBlock {
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
                ResourceLocation fileId = ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(), "textures/" + textureId.getPath() + ".png");
                ColorLightClient.LOGGER.info(blockid + " текстура " + "textures/" + textureId.getPath() + ".png");
                //типо оправление его на сканер текстуры
            }
            else{
                ResourceLocation modelId = ResourceLocation.parse(modid + ":block/" + blockid);
                Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json"));

                if (resource.isPresent()) {
                    try (InputStream stream = resource.get().open()) {

                        JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

                        if (json.has("textures")) {

                            JsonObject textures = json.getAsJsonObject("textures");

                            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                                ResourceLocation texture = ResourceLocation.parse(entry.getValue().getAsString());
                                ResourceLocation file = ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), "textures/" + texture.getPath() + ".png");

                                ColorLightClient.LOGGER.info(blockid + " текстура " + "textures/" + texture.getPath() + ".png");
                            }
                        }

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }
}
