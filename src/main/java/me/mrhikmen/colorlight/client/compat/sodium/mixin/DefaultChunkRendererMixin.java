package me.mrhikmen.colorlight.client.compat.sodium.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds the light volume's buffers for every terrain draw, right after Sodium opened its render pass
 * (26.2: the pass is created inside {@code render}, so it is picked up as a local).
 */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMixin implements SodiumHookMarkers.Draw {

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;setContext(Lcom/mojang/blaze3d/systems/RenderPass;Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void colorlight$bindLightVolume(ChunkRenderMatrices matrices,
                                            ChunkRenderListIterable renderLists,
                                            TerrainRenderPass renderPass,
                                            CameraTransform camera,
                                            FogParameters parameters,
                                            boolean indexedRenderingEnabled,
                                            GpuSampler terrainSampler,
                                            GpuBufferSlice uniformData,
                                            GpuBuffer sectionTimeInfo,
                                            CallbackInfo ci,
                                            @Local RenderPass pass) {
        ColorLightGpu.onDraw(pass, camera.intX, camera.intY, camera.intZ, camera.fracX, camera.fracY, camera.fracZ);
    }
}
