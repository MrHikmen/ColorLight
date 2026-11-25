package me.mrhikmen.colorlight.client.config.gui.sodium;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The built-in resource pack {@code colorlight:configured_lighting} (registered in ColorLightClient). The Sodium option
 * switches it. The state lives in the game's own selected-pack list, so the Resource Packs screen and the option
 * always show the same thing; {@code options.updateResourcePacks} writes it to options.txt.
 */
public final class ColorLightResourcePack {

    private static final Logger LOGGER = LoggerFactory.getLogger("ColorLight");
    private static final String PATH = "configured_lighting";

    private ColorLightResourcePack() {
    }

    /** The repository id of the pack, or empty if it is not registered. */
    private static Optional<String> id(PackRepository repository) {
        return repository.getAvailableIds().stream()
                .filter(id -> id.contains("colorlight") && id.endsWith(PATH))
                .findFirst();
    }

    public static boolean isEnabled() {
        PackRepository repository = Minecraft.getInstance().getResourcePackRepository();
        return id(repository).map(id -> repository.getSelectedIds().contains(id)).orElse(false);
    }

    public static void setEnabled(boolean enabled) {
        Minecraft minecraft = Minecraft.getInstance();
        PackRepository repository = minecraft.getResourcePackRepository();
        Optional<String> id = id(repository);
        if (id.isEmpty()) {
            LOGGER.warn("[ColorLight] resource pack '{}' is not registered, nothing to switch", PATH);
            return;
        }
        if (enabled)
            repository.addPack(id.get());
        else
            repository.removePack(id.get());
        minecraft.options.updateResourcePacks(repository);
    }
}
