package me.mrhikmen.colorlight.compat.lod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import me.mrhikmen.colorlight.core.light.ColorLightUtil;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

final class LodLightOverlayRenderer {

    private static final float MIN_RADIUS = 0.6f;
    private static final float RADIUS_PER_STRENGTH = 0.09f;
    private static final float MAX_ALPHA = 0.55f;

    static void renderSourceGlow(WorldRenderContext context, ILodTerrainProvider provider, ClientLevel level, BlockPos sourcePos, int packedColor, int maxRangeBlocks) {

        int r = ColorLightUtil.r(packedColor);
        int g = ColorLightUtil.g(packedColor);
        int b = ColorLightUtil.b(packedColor);

        int strength = Math.max(r, Math.max(g, b));
        if (strength <= 0)
            return;

        float strengthFactor = strength / (float) ColorLightUtil.MAX;
        float radius = MIN_RADIUS + RADIUS_PER_STRENGTH * Math.min(maxRangeBlocks, 15);
        float alpha = MAX_ALPHA * strengthFactor;

        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();

        BlockPos anchor = findSurfaceAnchor(provider, level, sourcePos);

        double cx = anchor.getX() + 0.5 - camera.x;
        double cy = anchor.getY() + 0.5 - camera.y;
        double cz = anchor.getZ() + 0.5 - camera.z;

        PoseStack poseStack = context.matrices();
        MultiBufferSource.BufferSource bufferSource = (MultiBufferSource.BufferSource) context.consumers();
        if (poseStack == null || bufferSource == null)
            return;

        poseStack.pushPose();
        poseStack.translate(cx, cy, cz);

        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.lightning());
        PoseStack.Pose pose = poseStack.last();

        emitOctahedron(buffer, pose, radius, r, g, b, alpha);

        poseStack.popPose();

        bufferSource.endBatch(RenderTypes.lightning());
    }

    private static BlockPos findSurfaceAnchor(ILodTerrainProvider provider, ClientLevel level, BlockPos sourcePos) {
        if (provider.isSolidAt(level, sourcePos))
            return sourcePos;

        BlockPos below = sourcePos.below();
        if (provider.isSolidAt(level, below))
            return below;

        return sourcePos;
    }

    private static void emitOctahedron(VertexConsumer buffer, PoseStack.Pose pose, float radius, int r, int g, int b, float alpha) {

        float[][] verts = {
                {0, radius, 0},
                {radius, 0, 0},
                {0, 0, radius},
                {-radius, 0, 0},
                {0, 0, -radius},
                {0, -radius, 0},
        };

        int[][] faces = {
                {0, 1, 2}, {0, 2, 3}, {0, 3, 4}, {0, 4, 1},
                {5, 2, 1}, {5, 3, 2}, {5, 4, 3}, {5, 1, 4},
        };

        for (int[] face : faces) {
            for (int idx : face) {
                float[] v = verts[idx];
                buffer.addVertex(pose, v[0], v[1], v[2]).setColor(r, g, b, (int) (alpha * 255));
            }
        }
    }

    private LodLightOverlayRenderer() {
    }
}
