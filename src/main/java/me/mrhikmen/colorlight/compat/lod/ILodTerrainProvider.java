package me.mrhikmen.colorlight.compat.lod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

public interface ILodTerrainProvider {

    String getId();

    boolean isEnabled();

    boolean isPositionInLodTerrain(ClientLevel level, BlockPos pos);

    float getEffectiveLightRangeBlocks();

    boolean isSolidAt(ClientLevel level, BlockPos pos);
}
