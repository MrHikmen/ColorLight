package me.mrhikmen.colorlight.client.core.resourcepack.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the data files of the active resource packs and collects what they define:
 * <pre>
 * assets/colorlight/settings.json              default settings (range, tint, scan weights, ...)
 * assets/colorlight/propagation/&lt;name&gt;.json    a light-spreading method, id  colorlight:&lt;name&gt;
 * assets/colorlight/block/&lt;name&gt;.json          block colours / strengths / methods (see {@link BlockJson})
 * </pre>
 * Everything is plain JSON - there is no scripting engine any more, so a resource pack can never run code; it can
 * only supply numbers and strings that this loader reads and validates. A broken file never breaks the game: its
 * error is logged, listed in {@link LoadedData#problems()} and the file is skipped (a propagation method that
 * fails to load simply is not registered, so the built-in one stays).
 */
public final class DataPackLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("ColorLight");

    public static final String NAMESPACE = "colorlight";

    private final DataSource source;

    private final Map<SettingKey, Object> settings = new EnumMap<>(SettingKey.class);
    private final Map<Identifier, JsonPropagationMethod> methods = new LinkedHashMap<>();
    private final BlockRules blocks = new BlockRules();
    private final List<String> problems = new ArrayList<>();

    private DataPackLoader(DataSource source) {
        this.source = source;
    }

    public static LoadedData load(DataSource source) {
        DataPackLoader loader = new DataPackLoader(source);
        loader.run();
        return new LoadedData(loader.settings, List.copyOf(loader.methods.values()), loader.blocks, List.copyOf(loader.problems));
    }

    private void run() {
        for (DataFile file : source.settings())
            readSettingsFile(file);

        for (DataFile file : source.propagation())
            readPropagationFile(file);

        for (DataFile file : source.blocks()) {
            String origin = file.origin("block/");
            int before = problems.size();
            BlockJson.parse(file.text(), origin, blocks, problems);
            for (int i = before; i < problems.size(); i++)
                LOGGER.warn("[ColorLight] {}", problems.get(i));
        }

        LOGGER.info("[ColorLight] resource packs: {} propagation method(s), {} block rule(s), {} problem(s)",
                methods.size(), blocks.size(), problems.size());
    }

    // ------------------------------------------------------------------------------------ parsing

    private static JsonElement parse(String text) {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(true); // tolerate // and /* */ comments and trailing commas, like the block files
        return JsonParser.parseReader(reader);
    }

    private void problem(DataFile file, String folder, String message) {
        String line = file.origin(folder) + ": " + message;
        problems.add(line);
        LOGGER.warn("[ColorLight] {}", line);
    }

    // ------------------------------------------------------------------------------------ settings

    private void readSettingsFile(DataFile file) {
        JsonElement root;
        try {
            root = parse(file.text());
        } catch (RuntimeException e) {
            problem(file, "", "invalid JSON: " + e.getMessage());
            return;
        }
        if (root.isJsonNull())
            return; // an empty file: nothing to apply
        if (!root.isJsonObject()) {
            problem(file, "", "settings.json must be a JSON object { \"light_range\": 12, ... }");
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            String name = entry.getKey();
            if (name.isEmpty() || name.startsWith("_"))
                continue; // keys starting with '_' are comments, same convention as the block files
            SettingKey setting = SettingKey.byJsonKey(name);
            if (setting == null) {
                problem(file, "", "unknown setting '" + name + "' (known: " + knownSettings() + ")");
                continue;
            }
            try {
                settings.put(setting, setting.coerce(entry.getValue()));
            } catch (IllegalArgumentException e) {
                problem(file, "", e.getMessage());
            }
        }
    }

    private static String knownSettings() {
        StringBuilder sb = new StringBuilder();
        for (SettingKey k : SettingKey.values())
            sb.append(sb.length() == 0 ? "" : ", ").append(k.jsonKey);
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------ propagation

    private void readPropagationFile(DataFile file) {
        JsonElement root;
        try {
            root = parse(file.text());
        } catch (RuntimeException e) {
            problem(file, "propagation/", "invalid JSON: " + e.getMessage());
            return;
        }

        Identifier id = Identifier.fromNamespaceAndPath(file.namespace(), file.name());
        try {
            PropagationSpec spec = PropagationSpec.fromJson(root, id.toString());
            String originPack = "resource pack '" + file.packId() + "' (" + file.namespace() + ":propagation/" + file.name() + ".json)";
            methods.put(id, new JsonPropagationMethod(id, spec, originPack));
        } catch (IllegalArgumentException e) {
            problem(file, "propagation/", "not registered: " + e.getMessage());
        }
    }
}
