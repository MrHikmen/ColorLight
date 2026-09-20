package me.mrhikmen.colorlight.compat.lod;

import java.util.ArrayList;
import java.util.List;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.compat.lod.voxy.ColorLightVoxyCompat;
import me.mrhikmen.colorlight.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.core.light.engine.SourceSnapshot;
import me.mrhikmen.colorlight.core.light.color.ColorLightUtil;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

public final class LodColorLightCompat {

    private static final List<ILodTerrainProvider> PROVIDERS = new ArrayList<>();
    private static boolean registered = false;

    private static final float MIN_RADIUS = 0.6f;
    private static final float RADIUS_PER_STRENGTH = 0.09f;
    private static final float MAX_ALPHA = 0.55f;

    /**
     * onExtract runs every frame, but which sources are in range/in LOD terrain barely changes from
     * one frame to the next. The glow list is rebuilt at most this often. A changed source set does NOT
     * force a rebuild on its own: while flying, chunks load and unload constantly, so the set changes
     * nearly every frame and that would bring back the per-frame rebuild this throttle exists to avoid.
     */
    private static final long REBUILD_INTERVAL_NANOS = 400_000_000L;

    private static ColorLightEngine lastEngine;
    private static long lastBuildNanos;

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

//        LevelExtractionEvents.END_EXTRACTION.register(LodColorLightCompat::onExtract);
//        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(LodLightOverlayRenderer::draw);
    }

    private static void onExtract(LevelExtractionContext context) {
        if (PROVIDERS.isEmpty())
            return;

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null) {
//            LodLightOverlayRenderer.setGlows(List.of());
            lastEngine = null;
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (level == null || player == null) {
//            LodLightOverlayRenderer.setGlows(List.of());
            lastEngine = null;
            return;
        }

        long now = System.nanoTime();
        if (engine == lastEngine && now - lastBuildNanos < REBUILD_INTERVAL_NANOS)
            return; // the glows already handed to the renderer are still good

        lastEngine = engine;
        lastBuildNanos = now;

        SourceSnapshot snapshot = engine.getSourceSnapshot();

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

//        List<LodLightOverlayRenderer.GlowState> glows = new ArrayList<>();

        for (BlockPos sourcePos : snapshot.positions()) {
            int color = engine.getColor(sourcePos);
            if (ColorLightUtil.isEmpty(color))
                continue;

            ILodTerrainProvider provider = findProviderFor(level, sourcePos);
            if (provider == null)
                continue;

            double dx = (sourcePos.getX() + 0.5) - px;
            double dy = (sourcePos.getY() + 0.5) - py;
            double dz = (sourcePos.getZ() + 0.5) - pz;
            double distSq = dx * dx + dy * dy + dz * dz;

            float range = provider.getEffectiveLightRangeBlocks();
            if (distSq > (double) range * range)
                continue;

//            LodLightOverlayRenderer.GlowState glow = buildGlow(provider, level, sourcePos, color, engine.getMaxRangeBlocks());
//            if (glow != null)
//                glows.add(glow);
        }
//        LodLightOverlayRenderer.setGlows(glows);
    }

//    private static LodLightOverlayRenderer.GlowState buildGlow(ILodTerrainProvider provider, ClientLevel level, BlockPos sourcePos, int packedColor, int maxRangeBlocks) {
//        int r = ColorLightUtil.r(packedColor);
//        int g = ColorLightUtil.g(packedColor);
//        int b = ColorLightUtil.b(packedColor);
//
//        int strength = Math.max(r, Math.max(g, b));
//        if (strength <= 0)
//            return null;
//
//        float strengthFactor = strength / (float) ColorLightUtil.MAX;
//        float radius = MIN_RADIUS + RADIUS_PER_STRENGTH * Math.min(maxRangeBlocks, 15);
//        float alpha = MAX_ALPHA * strengthFactor;
//
//        BlockPos anchor = findSurfaceAnchor(provider, level, sourcePos);
//
//        float cx = anchor.getX() + 0.5f;
//        float cy = anchor.getY() + 0.5f;
//        float cz = anchor.getZ() + 0.5f;
//
//        return new LodLightOverlayRenderer.GlowState(
//                cx - radius, cy - radius, cz - radius,
//                cx + radius, cy + radius, cz + radius,
//                r / 255f, g / 255f, b / 255f, alpha
//        );
//    }

    private static BlockPos findSurfaceAnchor(ILodTerrainProvider provider, ClientLevel level, BlockPos sourcePos) {
        if (provider.isSolidAt(level, sourcePos))
            return sourcePos;

        BlockPos below = sourcePos.below();
        if (provider.isSolidAt(level, below))
            return below;

        return sourcePos;
    }

    private static ILodTerrainProvider findProviderFor(ClientLevel level, BlockPos sourcePos) {
        for (ILodTerrainProvider provider : PROVIDERS) {
            if (!provider.isEnabled())
                continue;
            if (provider.isPositionInLodTerrain(level, sourcePos))
                return provider;
        }
        return null;
    }

    private LodColorLightCompat() {
    }
}
