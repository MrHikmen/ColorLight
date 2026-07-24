package me.mrhikmen.colorlight.config;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;

public class ColorLightConfig {

    public boolean ENABLE = true;

    public LinkedList<ColorLightSaveBlock> blocks = new LinkedList<>();

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("colorlight.json");

    public void load() {
        Gson gson = new Gson();

        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                ColorLightConfig loaded = gson.fromJson(reader, ColorLightConfig.class);

                this.ENABLE = loaded.ENABLE;
                this.blocks = loaded.blocks;

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