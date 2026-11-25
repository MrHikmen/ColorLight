package me.mrhikmen.colorlight.client.mixin.minecraft.renderer;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds the light volume's buffers to the pipeline that is about to draw (see {@link ColorLightGpu#bindEntityLight}).
 * Entities, items and particles need them after every pipeline switch, because the pass re-applies only the uniforms
 * it was given, not the ones of the pipeline it switched from.
 * <p>
 * The bind is done lazily, right before the first draw after a switch, and not at the tail of {@code setPipeline}.
 * Other mods also hook {@code setPipeline} (HDR Mod cancels it when the attachment formats don't match, e.g. for the
 * GUI item atlas). A cancelled callback skips every tail injector, so the buffers were never bound there and the
 * item pipeline drew with unset uniforms ({@code GlCommandEncoder.setupDraw}: "bufferView is null").
 * {@code require = 0}: without these the vanilla pipelines are used.
 */
@Mixin(FrontendRenderPass.class)
public abstract class FrontendRenderPassMixin {

    /** False after a pipeline switch, until the light buffers have been bound for the new pipeline. */
    @Unique
    private boolean colorlight$bound;

    @Inject(method = "setPipeline", at = @At("HEAD"), require = 0)
    private void colorlight$unbind(CallbackInfo ci) {
        this.colorlight$bound = false;
    }

    @Inject(method = {"drawIndexed", "draw"}, at = @At("HEAD"), require = 0)
    private void colorlight$bindBeforeDraw(CallbackInfo ci) {
        if (this.colorlight$bound)
            return;
        this.colorlight$bound = true;
        ColorLightGpu.bindEntityLight((RenderPass) (Object) this);
    }
}
