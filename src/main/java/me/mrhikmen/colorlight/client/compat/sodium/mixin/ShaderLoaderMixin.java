package me.mrhikmen.colorlight.client.compat.sodium.mixin;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Second, lower-level place where Sodium's terrain shaders are swapped for ColorLight's copies. Every Sodium shader
 * goes through {@code ShaderLoader.loadShader}, whatever code asks for it (Sodium's own {@code createShader}, or
 * another mod that recompiles or wraps Sodium's programs, like Veil's Sodium compat), so this does not depend on who
 * the caller is. Redirecting twice is harmless: the second time the id is already ColorLight's.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
public abstract class ShaderLoaderMixin {

    @ModifyVariable(method = "loadShader", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static ResourceLocation colorlight$redirect(ResourceLocation name) {
        if (!ColorLightGpu.gpuMode())
            return name;
        return ColorLightGpu.redirectSodiumShader(name);
    }
}
