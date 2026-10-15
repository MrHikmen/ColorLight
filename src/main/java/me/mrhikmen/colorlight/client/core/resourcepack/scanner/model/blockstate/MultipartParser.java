package me.mrhikmen.colorlight.client.core.resourcepack.scanner.model.blockstate;

import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.resourcepack.scanner.model.ModelTextureResolver;

import net.minecraft.resources.Identifier;

import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class MultipartParser {

    public MultipartParser(JsonObject json, BlockSettings entry) {

        JsonArray multipart = json.getAsJsonArray("multipart");

        List<Identifier> models = new ArrayList<>();

        for (JsonElement partElement : multipart) {

            JsonObject part = partElement.getAsJsonObject();

            if (!part.has("apply"))
                continue;

            ModelListJson.collectModels(part.get("apply"), models);
        }

        ModelTextureResolver.resolve(models, entry);

    }
}
