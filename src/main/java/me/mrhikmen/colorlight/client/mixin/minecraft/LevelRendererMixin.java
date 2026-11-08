package me.mrhikmen.colorlight.client.mixin.minecraft;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the world render, which is where entities, items and particles are drawn. The hand and the GUI are drawn
 * outside it and keep the vanilla light. The flag is set again at the next frame, so an exception that skips the
 * end of the render can't leave it on for long.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void colorlight$worldPassStart(CallbackInfo ci) {
        ColorLightGpu.setWorldPass(true);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void colorlight$worldPassEnd(CallbackInfo ci) {
        ColorLightGpu.setWorldPass(false);
    }
}
