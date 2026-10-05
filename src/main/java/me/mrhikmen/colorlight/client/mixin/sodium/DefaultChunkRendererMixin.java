package me.mrhikmen.colorlight.client.mixin.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;

import me.mrhikmen.colorlight.client.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Binds the light volume's buffers for every terrain draw, right after Sodium set the pipeline up. */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMixin implements SodiumHookMarkers.Draw {

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;setContext(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
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
                                            RenderPass pass,
                                            GpuSampler terrainSampler,
                                            GpuBufferSlice uniformData,
                                            GpuBuffer sectionTimeInfo,
                                            @Nullable OitStage stage,
                                            CallbackInfo ci) {
        ColorLightGpu.onDraw(pass, camera.intX, camera.intY, camera.intZ, camera.fracX, camera.fracY, camera.fracZ);
    }
}
