package me.mrhikmen.colorlight.client.compat.sodium.mixin;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Points Sodium's terrain program at ColorLight's copy of its shaders
 * ({@code assets/colorlight/shaders/blocks/colored_terrain.vsh/.fsh}).
 * <p>
 * Minecraft 1.21.1 / Sodium 0.8.13: Sodium compiles plain OpenGL programs ({@code ShaderLoader.loadShader}), so the
 * only thing to do here is to swap the shader identifier. The light volume's buffers are attached to the program
 * in {@code DefaultChunkRendererMixin}.
 * <p>
 * The injector is {@code require = 0}: if Sodium's code changed so that it doesn't match, the game still starts and
 * {@code ColorLightGpu.onDraw} notices the missing piece and falls back to vertex colours.
 */
@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMixin implements SodiumHookMarkers.Shader {

    @ModifyArg(
            method = "createShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderLoader;loadShader"
            ),
            index = 1,
            require = 0
    )
    private ResourceLocation colorlight$terrainShader(ResourceLocation original) {
        if (!ColorLightGpu.gpuMode())
            return original;
        return ColorLightGpu.redirectSodiumShader(original);
    }

    /**
     * Second place for the per-draw binding: the end of {@code begin}, when Sodium's program is current. The one in
     * {@code DefaultChunkRendererMixin} is matched against the body of {@code render}, which other mods may change;
     * this one only needs the method name. Calling both is harmless.
     */
    @Inject(method = "begin", at = @At("RETURN"), require = 0)
    private void colorlight$afterBegin(CallbackInfo ci) {
        if (ColorLightGpu.gpuMode())
            ColorLightGpu.onDraw();
    }
}
