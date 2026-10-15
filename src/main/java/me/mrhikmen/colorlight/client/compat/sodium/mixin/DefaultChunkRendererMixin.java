package me.mrhikmen.colorlight.client.compat.sodium.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
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
 * Attaches the light volume's buffers to Sodium's terrain program for every terrain draw, right after
 * {@code ShaderChunkRenderer.begin} compiled (the first time), bound and set up the program.
 */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMixin implements SodiumHookMarkers.Draw {

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;begin(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;Lcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/caffeinemc/mods/sodium/client/gl/buffer/GlTexelBuffer;)V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void colorlight$bindLightVolume(ChunkRenderMatrices matrices,
                                            CommandList commandList,
                                            ChunkRenderListIterable renderLists,
                                            TerrainRenderPass renderPass,
                                            CameraTransform camera,
                                            FogParameters parameters,
                                            boolean indexedRenderingEnabled,
                                            GpuSampler terrainSampler,
                                            GpuBufferSlice uniformBuffer,
                                            GlTexelBuffer sectionTimeInfoTexture,
                                            CallbackInfo ci) {
        ColorLightGpu.onDraw(camera.intX, camera.intY, camera.intZ, camera.fracX, camera.fracY, camera.fracZ);
    }
}
