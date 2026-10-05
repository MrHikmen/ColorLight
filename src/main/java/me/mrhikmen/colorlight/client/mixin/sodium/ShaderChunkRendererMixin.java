package me.mrhikmen.colorlight.client.mixin.sodium;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;

import me.mrhikmen.colorlight.client.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.gpu.SodiumHookMarkers;

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
 * Points Sodium's terrain pipelines at ColorLight's shader copy and gives them the two extra buffers it reads.
 * <p>
 * Every injector here is {@code require = 0}: if Sodium's code changed so that one doesn't match, the game still
 * starts and {@code ColorLightGpu.onDraw} notices the missing piece and falls back to vertex colours.
 */
@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMixin implements SodiumHookMarkers.Shader {

    /**
     * The bind group that holds {@code u_LightTex}. Sodium adds it to every pipeline that samples light (including the
     * order-independent-transparency accumulate stage), so extending it reaches all of them.
     */
    @Mutable
    @Shadow
    @Final
    public static BindGroupLayout LIGHT_GROUP;

    @Inject(method = "<clinit>", at = @At("TAIL"), require = 0)
    private static void colorlight$extendLightGroup(CallbackInfo ci) {
        if (!ColorLightGpu.gpuMode())
            return;

        LIGHT_GROUP = BindGroupLayout.builder()
                .withUniform("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("u_CLHeader", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .withUniform("u_CLData", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .withUniform("u_CLPool", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .build();
        ColorLightGpu.layoutPatched = true;
    }

    @ModifyArg(
            method = {"createShader", "createOITShader"},
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
            method = {"createShader", "createOITShader"},
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
