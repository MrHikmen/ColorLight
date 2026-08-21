package me.mrhikmen.colorlight.config;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;

public class ColorLightConfig {

    public boolean ENABLE = true;

    public int lightRangeBlocks = 15;
    public boolean USE_GPU_LIGHTING = true;
    public boolean SMOOTH_LIGHTING = true;

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

    public boolean VOXY_COMPAT_ENABLED = false;
    public boolean VOXY_FOLLOW_LOD_RENDER_DISTANCE = true;
    public int VOXY_LIGHT_RANGE_BLOCKS = 64;

    public LinkedList<BlockSettings> blocks = new LinkedList<>();

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("colorlight.json");

    public void load() {
        Gson gson = new Gson();

        if (Files.exists(PATH)) {
            try {
                String json = Files.readString(PATH);
                ColorLightConfig loaded = gson.fromJson(json, ColorLightConfig.class);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                this.ENABLE = loaded.ENABLE;
                this.blocks = loaded.blocks;
                this.lightRangeBlocks = loaded.lightRangeBlocks > 0 ? loaded.lightRangeBlocks : this.lightRangeBlocks;
                this.USE_GPU_LIGHTING = loaded.USE_GPU_LIGHTING;
                this.SMOOTH_LIGHTING = loaded.SMOOTH_LIGHTING;

                this.BRIGHTNESS_WEIGHT = loaded.BRIGHTNESS_WEIGHT > -1 ? loaded.BRIGHTNESS_WEIGHT : this.BRIGHTNESS_WEIGHT;
                this.LOCAL_WEIGHT = loaded.LOCAL_WEIGHT > -1 ? loaded.LOCAL_WEIGHT : this.LOCAL_WEIGHT;
                this.REGION_WEIGHT = loaded.REGION_WEIGHT > -1 ? loaded.REGION_WEIGHT : this.REGION_WEIGHT;
                this.ALPHA_WEIGHT = loaded.ALPHA_WEIGHT > -1 ? loaded.ALPHA_WEIGHT : this.ALPHA_WEIGHT;
                this.ANOMALY_WEIGHT = loaded.ANOMALY_WEIGHT > -1 ? loaded.ANOMALY_WEIGHT : this.ANOMALY_WEIGHT;
                this.SATURATION_WEIGHT = loaded.SATURATION_WEIGHT > -1 ? loaded.SATURATION_WEIGHT : this.SATURATION_WEIGHT;
                this.GLOWCOLORSCORE_WEIGHT = loaded.GLOWCOLORSCORE_WEIGHT > -1 ? loaded.GLOWCOLORSCORE_WEIGHT : this.GLOWCOLORSCORE_WEIGHT;
                this.WHITEPENALTY_WEIGHT = loaded.WHITEPENALTY_WEIGHT > -1 ? loaded.WHITEPENALTY_WEIGHT : this.WHITEPENALTY_WEIGHT;

                this.ENTITY_TRACKING_ENABLED = root.has("ENTITY_TRACKING_ENABLED") ? loaded.ENTITY_TRACKING_ENABLED : this.ENTITY_TRACKING_ENABLED;
                this.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE = root.has("ENTITY_CHECK_FOLLOW_RENDER_DISTANCE") ? loaded.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE : this.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE;
                this.ENTITY_CHECK_RADIUS_CHUNKS = loaded.ENTITY_CHECK_RADIUS_CHUNKS > 0 ? loaded.ENTITY_CHECK_RADIUS_CHUNKS : this.ENTITY_CHECK_RADIUS_CHUNKS;

                this.VOXY_COMPAT_ENABLED = root.has("VOXY_COMPAT_ENABLED") ? loaded.VOXY_COMPAT_ENABLED : this.VOXY_COMPAT_ENABLED;
                this.VOXY_FOLLOW_LOD_RENDER_DISTANCE = root.has("VOXY_FOLLOW_LOD_RENDER_DISTANCE") ? loaded.VOXY_FOLLOW_LOD_RENDER_DISTANCE : this.VOXY_FOLLOW_LOD_RENDER_DISTANCE;
                this.VOXY_LIGHT_RANGE_BLOCKS = loaded.VOXY_LIGHT_RANGE_BLOCKS > 0 ? loaded.VOXY_LIGHT_RANGE_BLOCKS : this.VOXY_LIGHT_RANGE_BLOCKS;

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try (Writer writer = Files.newBufferedWriter(PATH)) {
            gson.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}