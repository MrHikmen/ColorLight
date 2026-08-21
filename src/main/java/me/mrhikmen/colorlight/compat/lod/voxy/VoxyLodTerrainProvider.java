package me.mrhikmen.colorlight.compat.lod.voxy;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.compat.lod.ILodTerrainProvider;
import me.mrhikmen.colorlight.config.ColorLightConfig;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

public final class VoxyLodTerrainProvider implements ILodTerrainProvider {

    private static final int SECTION_SHIFT = 5;
    private static final int SECTION_MASK = 31;

    private static final float BLOCKS_PER_SECTION = 32f;

    @Override
    public String getId() {
        return "voxy";
    }

    @Override
    public boolean isEnabled() {
        return ColorLightClient.config.VOXY_COMPAT_ENABLED;
    }

    @Override
    public float getEffectiveLightRangeBlocks() {
        ColorLightConfig config = ColorLightClient.config;

        if (!config.VOXY_FOLLOW_LOD_RENDER_DISTANCE)
            return config.VOXY_LIGHT_RANGE_BLOCKS;

        try {
            return VoxyConfig.CONFIG.sectionRenderDistance * BLOCKS_PER_SECTION;
        } catch (Throwable t) {
            return config.VOXY_LIGHT_RANGE_BLOCKS;
        }
    }

    @Override
    public boolean isPositionInLodTerrain(ClientLevel level, BlockPos pos) {
        WorldEngine engine = safeGetEngine(level);
        if (engine == null)
            return false;

        WorldSection section = engine.acquireIfExists(
                0,
                pos.getX() >> SECTION_SHIFT,
                pos.getY() >> SECTION_SHIFT,
                pos.getZ() >> SECTION_SHIFT
        );

        if (section == null)
            return false;

        section.release();
        return true;
    }

    @Override
    public boolean isSolidAt(ClientLevel level, BlockPos pos) {
        WorldEngine engine = safeGetEngine(level);
        if (engine == null)
            return false;

        WorldSection section = engine.acquireIfExists(0,
                pos.getX() >> SECTION_SHIFT,
                pos.getY() >> SECTION_SHIFT,
                pos.getZ() >> SECTION_SHIFT
        );

        if (section == null)
            return false;

        try {
            int lx = pos.getX() & SECTION_MASK;
            int ly = pos.getY() & SECTION_MASK;
            int lz = pos.getZ() & SECTION_MASK;

            long voxel = section._unsafeGetRawDataArray()[WorldSection.getIndex(lx, ly, lz)];
            return !Mapper.isAir(voxel);
        } finally {
            section.release();
        }
    }

    private static WorldEngine safeGetEngine(ClientLevel level) {
        try {
            WorldIdentifier id = WorldIdentifier.of(level);
            if (id == null)
                return null;
            return id.getNullable();
        } catch (Throwable t) {
            return null;
        }
    }
}
