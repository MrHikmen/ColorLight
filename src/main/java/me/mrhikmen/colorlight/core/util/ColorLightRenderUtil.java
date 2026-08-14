package me.mrhikmen.colorlight.core.util;

import me.mrhikmen.colorlight.ColorLightClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

public final class ColorLightRenderUtil {

    private static final int MAX_DIRTY_RETRIES = 20;

    public static void setBlocksDirty(ClientLevel level, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1) {

        int sMinX = SectionPos.blockToSectionCoord(x0);
        int sMinY = SectionPos.blockToSectionCoord(y0);
        int sMinZ = SectionPos.blockToSectionCoord(z0);
        int sMaxX = SectionPos.blockToSectionCoord(x1);
        int sMaxY = SectionPos.blockToSectionCoord(y1);
        int sMaxZ = SectionPos.blockToSectionCoord(z1);

        Minecraft.getInstance().levelRenderer.setBlocksDirty(sMinX, sMinY, sMinZ, sMaxX, sMaxY, sMaxZ);
    }

    public static void setBlocksDirtySafe(ClientLevel level, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1) {
        trySetBlocksDirty(level, origin, x0, y0, z0, x1, y1, z1, 0);
    }

    public static void setBlocksDirty(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
        setBlocksDirty(level, null, x0, y0, z0, x1, y1, z1);
    }

    public static void setBlocksDirtySafe(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
        trySetBlocksDirty(level, null, x0, y0, z0, x1, y1, z1, 0);
    }

    private static void trySetBlocksDirty(ClientLevel level, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1, int attempt) {
        try {
            setBlocksDirty(level, origin, x0, y0, z0, x1, y1, z1);
        } catch (NullPointerException e) {
            if (attempt >= MAX_DIRTY_RETRIES) {
                ColorLightClient.LOGGER.warn("[ColorLight] Failed to rebuild chunks after {} attempts — " + "Sodium renderer never ended up being ready.", MAX_DIRTY_RETRIES);
                return;
            }
            Minecraft.getInstance().execute(() -> trySetBlocksDirty(level, origin, x0, y0, z0, x1, y1, z1, attempt + 1));
        }
    }

    private ColorLightRenderUtil() {
    }
}