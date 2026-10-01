package me.mrhikmen.colorlight.client.script;

import me.mrhikmen.colorlight.client.lua.LuaPackLoader;
import me.mrhikmen.colorlight.client.lua.ScriptFile;
import me.mrhikmen.colorlight.client.lua.ScriptSource;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Finds ColorLight's Lua files in the active resource packs:
 * <pre>
 * assets/colorlight/settings.lua            (every pack's copy, lowest priority first)
 * assets/colorlight/propagation/*.lua       (a pack's file replaces a lower pack's file of the same name)
 * assets/colorlight/block/*.json            (same; plain JSON, no Lua)
 * </pre>
 * The mod's own jar is a resource pack too, so the bundled grid.lua / smooth.lua are found the same way and can be
 * overridden by any pack placed above it.
 */
public final class ResourceScriptSource implements ScriptSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("ColorLight");

    private final ResourceManager manager;

    public ResourceScriptSource(ResourceManager manager) {
        this.manager = manager;
    }

    @Override
    public List<ScriptFile> settings() {
        List<ScriptFile> files = new ArrayList<>();
        Identifier id = Identifier.fromNamespaceAndPath(LuaPackLoader.NAMESPACE, "settings.lua");
        for (Resource resource : manager.getResourceStack(id)) { // lowest priority first
            ScriptFile file = read(resource, "settings");
            if (file != null)
                files.add(file);
        }
        return files;
    }

    @Override
    public List<ScriptFile> propagation() {
        return folder("propagation", ".lua");
    }

    @Override
    public List<ScriptFile> blocks() {
        return folder("block", ".json");
    }

    private List<ScriptFile> folder(String folder, String extension) {
        Map<Identifier, Resource> found = manager.listResources(folder,
                id -> LuaPackLoader.NAMESPACE.equals(id.getNamespace()) && id.getPath().endsWith(extension));

        // alphabetical by path, so the order is the same on every machine
        Map<String, Resource> byPath = new TreeMap<>();
        for (Map.Entry<Identifier, Resource> entry : found.entrySet())
            byPath.put(entry.getKey().getPath(), entry.getValue());

        List<ScriptFile> files = new ArrayList<>();
        for (Map.Entry<String, Resource> entry : byPath.entrySet()) {
            String path = entry.getKey();
            String name = path.substring(folder.length() + 1, path.length() - extension.length());
            ScriptFile file = read(entry.getValue(), name);
            if (file != null)
                files.add(file);
        }
        return files;
    }

    private static ScriptFile read(Resource resource, String name) {
        try (BufferedReader reader = resource.openAsReader()) {
            String text = reader.lines().collect(Collectors.joining("\n"));
            return new ScriptFile(LuaPackLoader.NAMESPACE, name, resource.sourcePackId(), text);
        } catch (IOException e) {
            LOGGER.warn("[ColorLight] could not read {} from pack '{}': {}", name, resource.sourcePackId(), e.getMessage());
            return null;
        }
    }
}
