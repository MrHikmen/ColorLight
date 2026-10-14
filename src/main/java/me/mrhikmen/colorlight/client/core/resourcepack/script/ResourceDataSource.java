package me.mrhikmen.colorlight.client.core.resourcepack.script;

import me.mrhikmen.colorlight.client.core.resourcepack.data.DataFile;
import me.mrhikmen.colorlight.client.core.resourcepack.data.DataPackLoader;
import me.mrhikmen.colorlight.client.core.resourcepack.data.DataSource;

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
 * Finds ColorLight's JSON files in the active resource packs:
 * <pre>
 * assets/colorlight/settings.json            (every pack's copy, lowest priority first)
 * assets/colorlight/propagation/*.json       (a pack's file replaces a lower pack's file of the same name)
 * assets/colorlight/block/*.json             (same)
 * </pre>
 * The mod's own jar is a resource pack too, so the bundled grid.json / smooth.json are found the same way and can
 * be overridden by any pack placed above it.
 */
public final class ResourceDataSource implements DataSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("ColorLight");

    private final ResourceManager manager;

    public ResourceDataSource(ResourceManager manager) {
        this.manager = manager;
    }

    @Override
    public List<DataFile> settings() {
        List<DataFile> files = new ArrayList<>();
        Identifier id = Identifier.fromNamespaceAndPath(DataPackLoader.NAMESPACE, "settings.json");
        for (Resource resource : manager.getResourceStack(id)) { // lowest priority first
            DataFile file = read(resource, "settings");
            if (file != null)
                files.add(file);
        }
        return files;
    }

    @Override
    public List<DataFile> propagation() {
        return folder("propagation", ".json");
    }

    @Override
    public List<DataFile> blocks() {
        return folder("block", ".json");
    }

    private List<DataFile> folder(String folder, String extension) {
        Map<Identifier, Resource> found = manager.listResources(folder,
                id -> DataPackLoader.NAMESPACE.equals(id.getNamespace()) && id.getPath().endsWith(extension));

        // alphabetical by path, so the order is the same on every machine
        Map<String, Resource> byPath = new TreeMap<>();
        for (Map.Entry<Identifier, Resource> entry : found.entrySet())
            byPath.put(entry.getKey().getPath(), entry.getValue());

        List<DataFile> files = new ArrayList<>();
        for (Map.Entry<String, Resource> entry : byPath.entrySet()) {
            String path = entry.getKey();
            String name = path.substring(folder.length() + 1, path.length() - extension.length());
            DataFile file = read(entry.getValue(), name);
            if (file != null)
                files.add(file);
        }
        return files;
    }

    private static DataFile read(Resource resource, String name) {
        try (BufferedReader reader = resource.openAsReader()) {
            String text = reader.lines().collect(Collectors.joining("\n"));
            return new DataFile(DataPackLoader.NAMESPACE, name, resource.sourcePackId(), text);
        } catch (IOException e) {
            LOGGER.warn("[ColorLight] could not read {} from pack '{}': {}", name, resource.sourcePackId(), e.getMessage());
            return null;
        }
    }
}
