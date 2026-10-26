package me.mrhikmen.colorlight.client.core.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

public final class ColorLightRenderUtil {

    public static void setBlocksDirty(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {

        int sMinX = SectionPos.blockToSectionCoord(x0);
        int sMinY = SectionPos.blockToSectionCoord(y0);
        int sMinZ = SectionPos.blockToSectionCoord(z0);
        int sMaxX = SectionPos.blockToSectionCoord(x1);
        int sMaxY = SectionPos.blockToSectionCoord(y1);
        int sMaxZ = SectionPos.blockToSectionCoord(z1);

        level.setSectionRangeDirty(sMinX, sMinY, sMinZ, sMaxX, sMaxY, sMaxZ);
    }

    /**
     * Marks a single render section dirty.
     *
     * @return false if the renderer isn't ready yet (Sodium throws an NPE while it initialises), in
     * which case the caller should simply try again on a later tick.
     */
    public static boolean setSectionDirty(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        try {
            level.setSectionRangeDirty(sectionX, sectionY, sectionZ, sectionX, sectionY, sectionZ);
            return true;
        } catch (NullPointerException e) {
            return false;
        }
    }

    private ColorLightRenderUtil() {
    }
}
