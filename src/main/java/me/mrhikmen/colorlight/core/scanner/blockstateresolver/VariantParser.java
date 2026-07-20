package me.mrhikmen.colorlight.core.scanner.blockstateresolver;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.core.scanner.PixelData;
import me.mrhikmen.colorlight.core.scanner.SearchBestPixel;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class VariantParser {

    public VariantParser(JsonObject json) {

        JsonObject variants = json.getAsJsonObject("variants");

        List<ResourceLocation> models = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {

            JsonElement variant = entry.getValue();

            if (variant.isJsonObject()) {

                JsonObject model = variant.getAsJsonObject();

                models.add(ResourceLocation.parse(model.get("model").getAsString()));

            } else if (variant.isJsonArray()) {

                for (JsonElement element : variant.getAsJsonArray()) {

                    JsonObject model = element.getAsJsonObject();

                    models.add(ResourceLocation.parse(model.get("model").getAsString()));
                }
            }
        }
        List<PixelData> bestPixels = new ArrayList<>();

        ModelResolver(models, bestPixels);
    }

    private static void ModelResolver(List<ResourceLocation> models, List<PixelData> bestPixels) {

        for (ResourceLocation modelId : models) {

            Optional<Resource> resourceId = Minecraft.getInstance().getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json"));

            if (resourceId.isEmpty())
                continue;

            try (InputStream streamId = resourceId.get().open()) {

                JsonObject jsonId = JsonParser.parseString(new String(streamId.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

                if (!jsonId.has("textures"))
                    continue;

                JsonObject textures = jsonId.getAsJsonObject("textures");

                for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {

                    String textureName = entry.getValue().getAsString();

                    while (textureName.startsWith("#")) {

                        String key = textureName.substring(1);

                        if (textures.has(key)) {
                            textureName = textures.get(key).getAsString();
                        }
                    }

                    ResourceLocation texture = ResourceLocation.parse(textureName);

                    bestPixels.add(SearchBestPixel.search(texture));
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        PixelData best = null;

        for (PixelData pixel : bestPixels) {

            if (best == null || pixel.score > best.score)
                best = pixel;
        }

        if (best != null) {
            ColorLightClient.LOGGER.info(best.r + " " + best.g + " " + best.b + " score = " + best.score);
        }
    }
}