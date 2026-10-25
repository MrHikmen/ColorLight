package me.mrhikmen.colorlight.client.mixin.sodium;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches the light volume's buffers to Sodium's terrain program for every terrain draw, right after
 * {@code ShaderChunkRenderer.begin} compiled (the first time), bound and set up the program.
 * <p>
 * Minecraft 1.21.1 / Sodium 0.8.13: the target is matched by name only and the handler takes no target arguments, so
 * the injection does not depend on Sodium's exact parameter lists (nor on obfuscated Minecraft type names in a
 * descriptor). The camera comes from the frame hook ({@code SodiumWorldRendererMixin}).
 */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMixin implements SodiumHookMarkers.Draw {

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;begin",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void colorlight$bindLightVolume(CallbackInfo ci) {
        ColorLightGpu.onDraw();
    }
}
