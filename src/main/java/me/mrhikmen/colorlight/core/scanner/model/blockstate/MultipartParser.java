package me.mrhikmen.colorlight.core.scanner.model.blockstate;

import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.scanner.model.ModelTextureResolver;

import net.minecraft.resources.ResourceLocation;

import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class MultipartParser {

    public MultipartParser(JsonObject json, int i, ColorLightConfig config) {

        JsonArray multipart = json.getAsJsonArray("multipart");

        List<ResourceLocation> models = new ArrayList<>();

        for (JsonElement partElement : multipart) {

            JsonObject part = partElement.getAsJsonObject();

            if (!part.has("apply"))
                continue;

            JsonElement apply = part.get("apply");

            if (apply.isJsonObject()) {
                addModel(apply.getAsJsonObject(), models);

            } else if (apply.isJsonArray()) {

                for (JsonElement element : apply.getAsJsonArray()) {
                    addModel(element.getAsJsonObject(), models);
                }

            }
        }

        ModelTextureResolver.resolve(models, i, config);

    }

    private static void addModel(JsonObject apply, List<ResourceLocation> models) {

        if (!apply.has("model"))
            return;

        models.add(ResourceLocation.parse(apply.get("model").getAsString()));
    }
}