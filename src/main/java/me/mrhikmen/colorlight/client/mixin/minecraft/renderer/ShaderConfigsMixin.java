package me.mrhikmen.colorlight.client.mixin.minecraft.renderer;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Compiles ColorLight's copies of the entity, item and particle shaders in place of the vanilla ones, while the GPU
 * pipeline is on. The pipelines keep their vanilla names; only the source text they are compiled from changes.
 * {@code require = 0}: without it the vanilla shaders are used.
 */
@Mixin(ShaderManager.Configs.class)
public abstract class ShaderConfigsMixin {

    @ModifyVariable(method = "getShader", at = @At("HEAD"), argsOnly = true, require = 0)
    private Identifier colorlight$entityShader(Identifier id) {
        return ColorLightGpu.entityShader(id);
    }
}
