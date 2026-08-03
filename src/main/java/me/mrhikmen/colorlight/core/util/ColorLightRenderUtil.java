package me.mrhikmen.colorlight.core.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

public final class ColorLightRenderUtil {

    public static void setBlocksDirty(ClientLevel level,
                                      int x0, int y0, int z0,
                                      int x1, int y1, int z1) {

        int sMinX = SectionPos.blockToSectionCoord(x0);
        int sMinY = SectionPos.blockToSectionCoord(y0);
        int sMinZ = SectionPos.blockToSectionCoord(z0);
        int sMaxX = SectionPos.blockToSectionCoord(x1);
        int sMaxY = SectionPos.blockToSectionCoord(y1);
        int sMaxZ = SectionPos.blockToSectionCoord(z1);

        level.setSectionRangeDirty(sMinX, sMinY, sMinZ, sMaxX, sMaxY, sMaxZ);
    }

    private ColorLightRenderUtil() {
    }
}
