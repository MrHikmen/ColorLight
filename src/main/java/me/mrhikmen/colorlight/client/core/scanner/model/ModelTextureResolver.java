package me.mrhikmen.colorlight.client.core.scanner.model;

import me.mrhikmen.colorlight.client.config.BlockSettings;
import me.mrhikmen.colorlight.client.core.scanner.texture.PixelData;
import me.mrhikmen.colorlight.client.core.scanner.texture.SearchBestPixel;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ModelTextureResolver {

    /**
     * Resolved texture map per model for the current resource reload. A block with many state
     * variants points at the same few models over and over, and the same model is shared between blocks
     * (torch / wall torch, ...); parsing its JSON and its parent chain once is enough.
     */
    private static final Map<Identifier, Map<String, String>> MODEL_CACHE = new HashMap<>();

    public static void clearCache() {
        MODEL_CACHE.clear();
    }

    public static void resolve(List<Identifier> models, BlockSettings data) {

        List<PixelData> bestPixels = new ArrayList<>();

        // repeats can't change the outcome (a later equal score never replaces an earlier one), so skip them
        for (Identifier modelId : new LinkedHashSet<>(models)) {

            Map<String, String> textures = MODEL_CACHE.get(modelId);
            if (textures == null) {
                textures = resolveTextures(modelId, new HashSet<>());
                MODEL_CACHE.put(modelId, textures);
            }

            for (Map.Entry<String, String> entry : textures.entrySet()) {

                String textureName = entry.getValue();
                int guard = 0;

                while (textureName.startsWith("#") && guard++ < 16) {

                    String key = textureName.substring(1);

                    if (textures.containsKey(key)) {
                        textureName = textures.get(key);
                    } else {
                        break;
                    }
                }

                if (textureName.startsWith("#"))
                    continue;

                Identifier texture = Identifier.parse(textureName);
                PixelData result = SearchBestPixel.search(texture);

                if (result != null)
                    bestPixels.add(result);
            }
        }

        PixelData best = null;

        for (PixelData pixel : bestPixels) {

            if (best == null || pixel.score > best.score)
                best = pixel;
        }

        if (best != null) {
            data.r = best.r;
            data.g = best.g;
            data.b = best.b;
        }
    }

    private static Map<String, String> resolveTextures(Identifier modelId, Set<Identifier> visited) {

        Map<String, String> textures = new HashMap<>();

        if (modelId == null || !visited.add(modelId))
            return textures;

        Identifier modelFile = Identifier.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json");
        Optional<Resource> modelResource = Minecraft.getInstance().getResourceManager().getResource(modelFile);

        if (modelResource.isEmpty())
            return textures;

        JsonObject modelJson;

        try (InputStream stream = modelResource.get().open()) {

            modelJson = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (modelJson.has("parent")) {

            Identifier parentId = Identifier.parse(modelJson.get("parent").getAsString());
            textures.putAll(resolveTextures(parentId, visited));

        }
        if (modelJson.has("textures")) {

            JsonObject localTextures = modelJson.getAsJsonObject("textures");

            for (Map.Entry<String, JsonElement> entry : localTextures.entrySet()) {
                textures.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return textures;
    }
}