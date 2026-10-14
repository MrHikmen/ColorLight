package me.mrhikmen.colorlight.client.compat.sodium.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Points Sodium's terrain pipelines at ColorLight's shader copy and gives them the three extra buffers it reads
 * (Minecraft 26.2 / Sodium 0.9.x: one {@code BIND_GROUP} for every terrain pipeline).
 * <p>
 * Every injector here is {@code require = 0}: if Sodium's code changed so that one doesn't match, the game still
 * starts and {@code ColorLightGpu.onDraw} notices the missing piece and falls back to vertex colours.
 */
@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMixin implements SodiumHookMarkers.Shader {

    /** The bind group of every terrain pipeline; extended with the light volume's texel buffers. */
    @Mutable
    @Shadow
    @Final
    public static BindGroupLayout BIND_GROUP;

    @Inject(method = "<clinit>", at = @At("TAIL"), require = 0)
    private static void colorlight$extendBindGroup(CallbackInfo ci) {
        if (!ColorLightGpu.gpuMode())
            return;

        BIND_GROUP = BindGroupLayout.builder()
                .withSampler("u_LightTex")
                .withSampler("u_BlockTex")
                .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
                .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .withUniform("u_CLHeader", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .withUniform("u_CLData", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .withUniform("u_CLPool", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .build();
        ColorLightGpu.layoutPatched = true;
    }

    @ModifyArg(
            method = "createShader",
            at = @At(value = "INVOKE", target = "withVertexShader"),
            index = 0,
            require = 0
    )
    private Identifier colorlight$vertexShader(Identifier original) {
        if (ColorLightGpu.gpuMode() && ColorLightGpu.SODIUM_TERRAIN_SHADER.equals(original)) {
            ColorLightGpu.vertexRedirected = true;
            return ColorLightGpu.COLORLIGHT_TERRAIN_SHADER;
        }
        return original;
    }

    @ModifyArg(
            method = "createShader",
            at = @At(value = "INVOKE", target = "withFragmentShader"),
            index = 0,
            require = 0
    )
    private Identifier colorlight$fragmentShader(Identifier original) {
        if (ColorLightGpu.gpuMode() && ColorLightGpu.SODIUM_TERRAIN_SHADER.equals(original)) {
            ColorLightGpu.fragmentRedirected = true;
            return ColorLightGpu.COLORLIGHT_TERRAIN_SHADER;
        }
        return original;
    }
}
