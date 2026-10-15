package me.mrhikmen.colorlight.client.compat.sodium.mixin;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Points Sodium's terrain program at ColorLight's copy of its shaders
 * ({@code assets/colorlight/shaders/blocks/colored_terrain.vsh/.fsh}).
 * <p>
 * Minecraft 26.1.2 / Sodium 0.9.2: Sodium compiles plain OpenGL programs ({@code ShaderLoader.loadShader}), so the
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
                    target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderLoader;loadShader(Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/Identifier;Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;)Lnet/caffeinemc/mods/sodium/client/gl/shader/GlShader;"
            ),
            index = 1,
            require = 0
    )
    private Identifier colorlight$terrainShader(Identifier original) {
        if (!ColorLightGpu.gpuMode())
            return original;
        return ColorLightGpu.redirectSodiumShader(original);
    }
}
