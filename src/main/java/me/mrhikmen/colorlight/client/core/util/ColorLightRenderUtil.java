package me.mrhikmen.colorlight.client.core.util;

import me.mrhikmen.colorlight.client.ColorLightClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class ColorLightRenderUtil {

    private static final int MAX_DIRTY_RETRIES = 20;

    /**
     * Marks every render section touching the given block box dirty. Goes through the level renderer's
     * {@code setBlocksDirty}, which is the method Sodium redirects to its own renderer on 1.21.1.
     */
    public static void setBlocksDirty(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
        Minecraft.getInstance().levelRenderer.setBlocksDirty(x0, y0, z0, x1, y1, z1);
    }

    /**
     * Marks a single render section dirty.
     *
     * @return false if the renderer isn't ready yet (Sodium throws an NPE while it initialises), in
     * which case the caller should simply try again on a later tick.
     */
    public static boolean setSectionDirty(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        try {
            int x = sectionX << 4;
            int y = sectionY << 4;
            int z = sectionZ << 4;
            Minecraft.getInstance().levelRenderer.setBlocksDirty(x, y, z, x + 15, y + 15, z + 15);
            return true;
        } catch (NullPointerException e) {
            return false;
        }
    }

    public static void setBlocksDirtySafe(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
        trySetBlocksDirty(level, x0, y0, z0, x1, y1, z1, 0);
    }

    private static void trySetBlocksDirty(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1, int attempt) {
        try {
            setBlocksDirty(level, x0, y0, z0, x1, y1, z1);
        } catch (NullPointerException e) {
            if (attempt >= MAX_DIRTY_RETRIES) {
                ColorLightClient.LOGGER.warn("[ColorLight] Failed to rebuild chunks after {} attempts — Sodium renderer never ended up being ready.", MAX_DIRTY_RETRIES);
                return;
            }
            Minecraft.getInstance().execute(() -> trySetBlocksDirty(level, x0, y0, z0, x1, y1, z1, attempt + 1));
        }
    }

    private ColorLightRenderUtil() {
    }
}
