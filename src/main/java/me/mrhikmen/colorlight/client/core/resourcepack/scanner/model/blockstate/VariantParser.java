package me.mrhikmen.colorlight.client.core.resourcepack.scanner.model.blockstate;

import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.resourcepack.scanner.model.ModelTextureResolver;

import net.minecraft.resources.ResourceLocation;

import java.util.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class VariantParser {

    public VariantParser(JsonObject json, BlockSettings settings) {

        JsonObject variants = json.getAsJsonObject("variants");
        List<ResourceLocation> models = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            ModelListJson.collectModels(entry.getValue(), models);
        }

        ModelTextureResolver.resolve(models, settings);

    }
}
