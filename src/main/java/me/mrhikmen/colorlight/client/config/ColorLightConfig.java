package me.mrhikmen.colorlight.client.config;

import com.google.gson.*;
import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.resourcepack.data.SettingKey;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ColorLight's settings file ({@code config/colorlight.json}).
 * <p>
 * <b>Resource packs can provide defaults</b> ({@code assets/colorlight/settings.json}, see {@link SettingKey}). The rule:
 * <ul>
 *     <li>a setting the player has changed in ColorLight's GUI is theirs - it is remembered in
 *     {@link #userOverrides} and the pack never touches it again;</li>
 *     <li>every other setting that a pack lists takes the pack's value while that pack is active;</li>
 *     <li>pack values are never written into the file as if the player had chosen them: when the pack goes away,
 *     the player's own (or the mod's) values are back.</li>
 * </ul>
 */
public class ColorLightConfig {

    public boolean ENABLE = true;
    public int lightRangeBlocks = 15;
    public boolean SMOOTH_LIGHTING = true;
    public float TINT_GAMMA = 0.55f;
    public String PROPAGATION_MODE = "colorlight:grid";
    public String DYNAMIC_PROPAGATION = "colorlight:smooth";

    public boolean GPU_PIPELINE = true;
    public int GPU_LIGHT_SECTIONS = 768;

    public int BRIGHTNESS_WEIGHT     = 100;
    public int LOCAL_WEIGHT          = 100;
    public int REGION_WEIGHT         = 0;
    public int ALPHA_WEIGHT          = 0;
    public int ANOMALY_WEIGHT        = 100;
    public int SATURATION_WEIGHT     = 100;
    public int GLOWCOLORSCORE_WEIGHT = 100;
    public int WHITEPENALTY_WEIGHT   = 100;

    public boolean ENTITY_TRACKING_ENABLED = true;
    public boolean ENTITY_CHECK_FOLLOW_RENDER_DISTANCE = true;
    public int ENTITY_CHECK_RADIUS_CHUNKS = 12;

    public boolean VOXY_LIGHT = true;
    public boolean DH_LIGHT = true;

    public boolean CREATE_CONTRAPTION_LIGHT = true;

    public boolean SABLE_CROSS_LIGHT = true;
    public int SABLE_LIGHT_RANGE_BLOCKS = 15;
    public boolean SABLE_SUBLEVEL_LIGHT = true;
    public String SABLE_PROPAGATION = "";
    public net.minecraft.resources.ResourceLocation sablePropagationId() {
        String raw = (SABLE_PROPAGATION == null || SABLE_PROPAGATION.isBlank()) ? DYNAMIC_PROPAGATION : SABLE_PROPAGATION;
        return me.mrhikmen.colorlight.client.core.light.propagation.PropagationMethodRegistry.parse(raw);
    }

    public LinkedList<BlockSettings> blocks = new LinkedList<>();

    public List<String> userOverrides = new ArrayList<>();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("colorlight.json");
    private static volatile Map<SettingKey, Object> packDefaults = new EnumMap<>(SettingKey.class);
    private final transient Map<SettingKey, Object> appliedFromPack = new EnumMap<>(SettingKey.class);
    private final transient Map<SettingKey, Object> fileValues = new EnumMap<>(SettingKey.class);

    public void load() {
        Gson gson = new Gson();

        if (Files.exists(PATH)) {
            try {
                String json = Files.readString(PATH);
                ColorLightConfig loaded = gson.fromJson(json, ColorLightConfig.class);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                this.ENABLE = loaded.ENABLE;

                mergeBlocks(loaded.blocks);
                this.lightRangeBlocks = loaded.lightRangeBlocks > 0 ? loaded.lightRangeBlocks : this.lightRangeBlocks;
                this.SMOOTH_LIGHTING = loaded.SMOOTH_LIGHTING;
                this.TINT_GAMMA = loaded.TINT_GAMMA >= 0f ? loaded.TINT_GAMMA : this.TINT_GAMMA;
                this.PROPAGATION_MODE = loaded.PROPAGATION_MODE != null ? loaded.PROPAGATION_MODE : this.PROPAGATION_MODE;
                this.DYNAMIC_PROPAGATION = root.has("DYNAMIC_PROPAGATION") && loaded.DYNAMIC_PROPAGATION != null ? loaded.DYNAMIC_PROPAGATION : this.DYNAMIC_PROPAGATION;

                this.GPU_PIPELINE = root.has("GPU_PIPELINE") ? loaded.GPU_PIPELINE : this.GPU_PIPELINE;
                this.GPU_LIGHT_SECTIONS = loaded.GPU_LIGHT_SECTIONS >= 64 ? Math.min(4096, loaded.GPU_LIGHT_SECTIONS) : this.GPU_LIGHT_SECTIONS;

                this.BRIGHTNESS_WEIGHT = loaded.BRIGHTNESS_WEIGHT > -1 ? loaded.BRIGHTNESS_WEIGHT : this.BRIGHTNESS_WEIGHT;
                this.LOCAL_WEIGHT = loaded.LOCAL_WEIGHT > -1 ? loaded.LOCAL_WEIGHT : this.LOCAL_WEIGHT;
                this.REGION_WEIGHT = loaded.REGION_WEIGHT > -1 ? loaded.REGION_WEIGHT : this.REGION_WEIGHT;
                this.ALPHA_WEIGHT = loaded.ALPHA_WEIGHT > -1 ? loaded.ALPHA_WEIGHT : this.ALPHA_WEIGHT;
                this.ANOMALY_WEIGHT = loaded.ANOMALY_WEIGHT > -1 ? loaded.ANOMALY_WEIGHT : this.ANOMALY_WEIGHT;
                this.SATURATION_WEIGHT = loaded.SATURATION_WEIGHT > -1 ? loaded.SATURATION_WEIGHT : this.SATURATION_WEIGHT;
                this.GLOWCOLORSCORE_WEIGHT = loaded.GLOWCOLORSCORE_WEIGHT > -1 ? loaded.GLOWCOLORSCORE_WEIGHT : this.GLOWCOLORSCORE_WEIGHT;
                this.WHITEPENALTY_WEIGHT = loaded.WHITEPENALTY_WEIGHT > -1 ? loaded.WHITEPENALTY_WEIGHT : this.WHITEPENALTY_WEIGHT;

                this.VOXY_LIGHT = loaded.VOXY_LIGHT;
                this.DH_LIGHT = loaded.DH_LIGHT;

                this.ENTITY_TRACKING_ENABLED = root.has("ENTITY_TRACKING_ENABLED") ? loaded.ENTITY_TRACKING_ENABLED : this.ENTITY_TRACKING_ENABLED;
                this.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE = root.has("ENTITY_CHECK_FOLLOW_RENDER_DISTANCE") ? loaded.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE : this.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE;
                this.ENTITY_CHECK_RADIUS_CHUNKS = loaded.ENTITY_CHECK_RADIUS_CHUNKS > 0 ? loaded.ENTITY_CHECK_RADIUS_CHUNKS : this.ENTITY_CHECK_RADIUS_CHUNKS;


                this.CREATE_CONTRAPTION_LIGHT = root.has("CREATE_CONTRAPTION_LIGHT") ? loaded.CREATE_CONTRAPTION_LIGHT : this.CREATE_CONTRAPTION_LIGHT;

                this.SABLE_CROSS_LIGHT = root.has("SABLE_CROSS_LIGHT") ? loaded.SABLE_CROSS_LIGHT : this.SABLE_CROSS_LIGHT;
                this.SABLE_SUBLEVEL_LIGHT = root.has("SABLE_SUBLEVEL_LIGHT") ? loaded.SABLE_SUBLEVEL_LIGHT : this.SABLE_SUBLEVEL_LIGHT;
                this.SABLE_PROPAGATION = loaded.SABLE_PROPAGATION != null ? loaded.SABLE_PROPAGATION : "";
                this.SABLE_LIGHT_RANGE_BLOCKS = loaded.SABLE_LIGHT_RANGE_BLOCKS > 0 ? Math.min(32, loaded.SABLE_LIGHT_RANGE_BLOCKS) : this.SABLE_LIGHT_RANGE_BLOCKS;

                this.userOverrides = loaded.userOverrides != null ? new ArrayList<>(loaded.userOverrides) : new ArrayList<>();
            } catch (IOException e) {
                ColorLightClient.LOGGER.error("[ColorLight] Failed to read config file {}", PATH, e);
            }
        }

        rememberFileValues();
        applyPackDefaults();
    }

    private void mergeBlocks(LinkedList<BlockSettings> fromFile) {
        if (fromFile == null)
            fromFile = new LinkedList<>();
        java.util.Map<String, BlockSettings> existing = new java.util.HashMap<>();
        for (BlockSettings e : blocks)
            existing.putIfAbsent(e.block, e);

        LinkedList<BlockSettings> merged = new LinkedList<>();
        for (BlockSettings loaded : fromFile) {
            BlockSettings keep = existing.get(loaded.block);
            if (keep != null) {
                keep.r = loaded.r;
                keep.g = loaded.g;
                keep.b = loaded.b;
                keep.light = loaded.light;
                keep.enable = loaded.enable;
                keep.edit = loaded.edit;
                keep.propagation = loaded.propagation == null ? "" : loaded.propagation;
                merged.add(keep);
                existing.remove(loaded.block);
            } else {
                merged.add(loaded);
            }
        }
        this.blocks.clear();
        this.blocks.addAll(merged);
    }

    public void setPackDefaults(Map<SettingKey, Object> defaults) {
        packDefaults = new EnumMap<>(SettingKey.class);
        packDefaults.putAll(defaults);
        applyPackDefaults();
    }

    private void applyPackDefaults() {
        for (Map.Entry<SettingKey, Object> e : appliedFromPack.entrySet()) {
            if (!userOverrides.contains(e.getKey().jsonKey) && fileValues.containsKey(e.getKey()))
                write(e.getKey(), fileValues.get(e.getKey()));
        }
        appliedFromPack.clear();
        for (Map.Entry<SettingKey, Object> e : packDefaults.entrySet()) {
            SettingKey key = e.getKey();
            if (userOverrides.contains(key.jsonKey))
                continue;
            write(key, e.getValue());
            appliedFromPack.put(key, e.getValue());
        }
    }

    private void rememberFileValues() {
        fileValues.clear();
        appliedFromPack.clear();
        for (SettingKey key : SettingKey.values())
            fileValues.put(key, read(key));
    }

    public void save() {
        for (Map.Entry<SettingKey, Object> e : appliedFromPack.entrySet()) {
            SettingKey key = e.getKey();
            if (!Objects.equals(read(key), e.getValue()) && !userOverrides.contains(key.jsonKey)) {
                userOverrides.add(key.jsonKey);
                fileValues.put(key, read(key));
            }
        }
        for (SettingKey key : SettingKey.values()) {
            if (userOverrides.contains(key.jsonKey))
                fileValues.put(key, read(key));
        }
        Map<SettingKey, Object> current = new EnumMap<>(SettingKey.class);
        for (SettingKey key : appliedFromPack.keySet()) {
            if (userOverrides.contains(key.jsonKey))
                continue;
            current.put(key, read(key));
            write(key, fileValues.get(key));
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer writer = Files.newBufferedWriter(PATH)) {
            gson.toJson(this, writer);
        } catch (IOException e) {
            ColorLightClient.LOGGER.error("[ColorLight] Failed to write config file {}", PATH, e);
        } finally {
            for (Map.Entry<SettingKey, Object> e : current.entrySet())
                write(e.getKey(), e.getValue());
        }
    }

    private Object read(SettingKey key) {
        try {
            return field(key).get(this);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(SettingKey key, Object value) {
        try {
            field(key).set(this, value);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(SettingKey key) throws NoSuchFieldException {
        return ColorLightConfig.class.getField(key.field);
    }
}
