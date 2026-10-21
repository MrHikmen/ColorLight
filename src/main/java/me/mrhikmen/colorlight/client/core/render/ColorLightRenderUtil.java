package me.mrhikmen.colorlight.client.core.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class ColorLightRenderUtil {

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

    private ColorLightRenderUtil() {
    }
}
