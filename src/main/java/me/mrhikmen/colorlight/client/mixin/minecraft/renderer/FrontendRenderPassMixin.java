package me.mrhikmen.colorlight.client.mixin.minecraft.renderer;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After every pipeline switch, binds the light volume's buffers to the pipeline if it has the light bind group
 * (see {@link ColorLightGpu#bindEntityLight}). Entities, items and particles need them on every switch, because the
 * pass re-applies only the uniforms it was given, not the ones of the pipeline it switched from.
 */
@Mixin(FrontendRenderPass.class)
public abstract class FrontendRenderPassMixin {

    @Inject(method = "setPipeline", at = @At("TAIL"), require = 0)
    private void colorlight$bindEntityLight(CallbackInfo ci) {
        ColorLightGpu.bindEntityLight((RenderPass) (Object) this);
    }
}
