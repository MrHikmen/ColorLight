package me.mrhikmen.colorlight.compat.lambdynlights;

import me.mrhikmen.colorlight.ColorLightClient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.lambdaurora.lambdynlights.api.item.ItemLightSource;

import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditions;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lets ColorLight respect an explicit {@code "luminance": 0} in LambDynamicLights' item light files
 * ({@code assets/<namespace>/dynamiclights/item/*.json}).
 * <p>
 * LambDynamicLights answers "how bright is this item" with the <b>maximum</b> over every matching source, and only
 * falls back to the block's own light when nothing matched. ColorLight used to add its own sources to that maximum
 * (see {@link ColorLightLambDynLightsInitializer}) and never asked LambDynamicLights anything, so a resource pack that
 * set an item to 0 was overridden by ColorLight both in LambDynamicLights' light and in ColorLight's coloured light.
 * <p>
 * This class loads the files the same way LambDynamicLights does (same folder, same codec, same registries, same
 * {@code silence_error} / Fabric load-condition handling) but <b>without</b> ColorLight's own contribution, so
 * ColorLight can tell "a resource pack turned this item's light off" from "nothing says anything about it".
 */
public final class LdlItemLuminanceOverrides {

    private static final String RESOURCE_PATH = "dynamiclights/item";
    private static final String SILENCE_ERROR_KEY = "silence_error";

    /** Item light sources defined by resource packs / other mods (never ColorLight's own). */
    private static volatile List<ItemLightSource> packSources = List.of();

    /**
     * (Re)reads the item light files. Called from LambDynamicLights' registration event, which fires every time it
     * applies its data (world join, data reload, resource reload), so the list stays in step with what it uses.
     */
    public static void reload(HolderLookup.Provider registries) {
        List<ItemLightSource> loaded = new ArrayList<>();

        try {
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            var files = Minecraft.getInstance().getResourceManager()
                    .listResources(RESOURCE_PATH, path -> path.getPath().endsWith(".json"));

            for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
                try (var reader = new InputStreamReader(file.getValue().open(), StandardCharsets.UTF_8)) {
                    JsonElement raw = JsonParser.parseReader(reader);
                    if (!raw.isJsonObject())
                        continue;

                    JsonObject json = raw.getAsJsonObject();
                    json.remove(SILENCE_ERROR_KEY);

//                    if (!conditionsAllow(ops, registries, json))
//                        continue;

                    ItemLightSource.CODEC.parse(ops, json).result().ifPresent(loaded::add);
                } catch (IOException | RuntimeException e) {
                    // A broken file: LambDynamicLights reports it. If it cannot be read it cannot switch anything off.
                }
            }
        } catch (Throwable t) {
            // Never let this take the game down. Without the list ColorLight simply behaves as before.
            ColorLightClient.LOGGER.warn("[ColorLight] Could not read LambDynamicLights item light files", t);
            loaded.clear();
        }

        packSources = List.copyOf(loaded);
    }

    /** Same rule as LambDynamicLights: a file whose Fabric load conditions fail isn't applied at all. */
//    private static boolean conditionsAllow(RegistryOps<JsonElement> ops, HolderLookup.Provider registries, JsonObject json) {
//        if (!json.has(ResourceConditions.CONDITIONS_KEY))
//            return true;
//
//        var conditions = ResourceCondition.CONDITION_CODEC.parse(ops, json.get(ResourceConditions.CONDITIONS_KEY));
//        if (conditions.isSuccess())
//            return conditions.getOrThrow().test(registries);
//
//        // unparseable conditions: LambDynamicLights logs it and applies the file anyway
//        return true;
//    }

    /**
     * True if a resource pack (or mod) explicitly gives this stack a luminance of 0 in LambDynamicLights and nothing
     * else gives it more - i.e. the item is meant to emit no dynamic light, so ColorLight must not treat it as one.
     * <p>
     * Items no file mentions are <b>not</b> disabled, whatever their block's light. If several files match, the
     * highest luminance wins, exactly like in LambDynamicLights.
     */
    public static boolean isDisabled(ItemStack stack) {
        List<ItemLightSource> sources = packSources;
        if (sources.isEmpty())
            return false;

        boolean matched = false;
        for (ItemLightSource source : sources) {
            if (!source.predicate().test(stack))
                continue;

            matched = true;
            if (source.getLuminance(stack) > 0)
                return false;
        }
        return matched;
    }

    private LdlItemLuminanceOverrides() {
    }
}
