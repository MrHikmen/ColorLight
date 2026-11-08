package me.mrhikmen.colorlight.client.mixin.minecraft;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the first-person hand (renderItemInHand draws it in its own pass, after the world). The hand's vertices are in
 * view space, so its shaders light it from the camera's position (see ColorLightGpu.setHandPass).
 * {@code require = 0}: without it the hand keeps the vanilla light.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"), require = 0)
    private void colorlight$handPassStart(CallbackInfo ci) {
        ColorLightGpu.setHandPass(true);
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"), require = 0)
    private void colorlight$handPassEnd(CallbackInfo ci) {
        ColorLightGpu.setHandPass(false);
    }
}
