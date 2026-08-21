package me.mrhikmen.colorlight.compat.lod;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.compat.lod.voxy.ColorLightVoxyCompat;
import me.mrhikmen.colorlight.core.light.ColorLightEngine;
import me.mrhikmen.colorlight.core.light.ColorLightEngineHolder;
import me.mrhikmen.colorlight.core.light.ColorLightUtil;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class LodColorLightCompat {

    private static final List<ILodTerrainProvider> PROVIDERS = new ArrayList<>();
    private static boolean registered = false;

    public static void register() {
        if (registered)
            return;
        registered = true;

        if (ColorLightVoxyCompat.isPresent()) {
            PROVIDERS.add(new me.mrhikmen.colorlight.compat.lod.voxy.VoxyLodTerrainProvider());
            ColorLightClient.LOGGER.info("[ColorLight] Voxy LOD compatibility enabled");
        }

        if (PROVIDERS.isEmpty())
            return;

        WorldRenderEvents.END_MAIN.register(LodColorLightCompat::onRenderAfterTranslucent);
    }

    private static void onRenderAfterTranslucent(WorldRenderContext context) {
        if (PROVIDERS.isEmpty())
            return;

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return;

        for (BlockPos sourcePos : engine.getSourcePositions()) {
            int color = engine.getColor(sourcePos);
            if (ColorLightUtil.isEmpty(color))
                continue;

            ILodTerrainProvider provider = findProviderFor(level, sourcePos);
            if (provider == null)
                continue;

            double distSq = Minecraft.getInstance().gameRenderer.getMainCamera().position().distanceToSqr(
                    sourcePos.getX() + 0.5, sourcePos.getY() + 0.5, sourcePos.getZ() + 0.5
            );
            float range = provider.getEffectiveLightRangeBlocks();
            if (distSq > (double) range * range)
                continue;

            LodLightOverlayRenderer.renderSourceGlow(context, provider, level, sourcePos, color, engine.getMaxRangeBlocks());
        }
    }

    private static ILodTerrainProvider findProviderFor(ClientLevel level, BlockPos sourcePos) {
        for (ILodTerrainProvider provider : PROVIDERS) {
            if (!provider.isEnabled())
                continue;
            if (provider.isPositionInLodTerrain(level, sourcePos))
                return provider;
        }
        for (ILodTerrainProvider provider : PROVIDERS) {
            if (provider.isPositionInLodTerrain(level, sourcePos))
                return provider;
        }
        return null;
    }

    private LodColorLightCompat() {
    }
}
