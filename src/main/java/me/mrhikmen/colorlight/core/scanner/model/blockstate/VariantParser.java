package me.mrhikmen.colorlight.core.scanner.model.blockstate;

import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.scanner.model.ModelTextureResolver;

import net.minecraft.resources.ResourceLocation;

import java.util.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class VariantParser {

    public VariantParser(JsonObject json, int i, ColorLightConfig config) {

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

        ModelTextureResolver.resolve(models, i, config);

    }
}