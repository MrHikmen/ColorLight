package me.mrhikmen.colorlight.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ColorLightConfig {

    public boolean ENABLE = true;

    public LinkedList<ResourceLocation> blocklist = new LinkedList<>();

    public LinkedList<Integer> rgb = new LinkedList<>();

    public LinkedList<Integer> light = new LinkedList<>();

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("colorlight.json");

    public static ColorLightConfig CONFIG = new ColorLightConfig();

    public static void load() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                CONFIG = gson.fromJson(reader, ColorLightConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        save();
    }

    public static void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try (Writer writer = Files.newBufferedWriter(PATH)) {
            gson.toJson(CONFIG, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
