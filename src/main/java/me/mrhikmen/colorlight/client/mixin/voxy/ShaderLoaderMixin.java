package me.mrhikmen.colorlight.client.mixin.voxy;

import me.mrhikmen.colorlight.client.compat.lod.VoxyLightBridge;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy turns its shader files into source text in {@code ShaderLoader.parse} (it reads them from its own jar, so a
 * resource pack can't replace them). We look at every result and change the LOD vertex shader only.
 * <p>
 * Targeted by name so that ColorLight needs no Voxy at compile time; {@code ColorLightVoxyPlugin} keeps this out when
 * Voxy isn't installed.
 */
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader")
public abstract class ShaderLoaderMixin {

    @Inject(method = "parse", at = @At("RETURN"), cancellable = true, require = 0)
    private static void colorlight$addFarLight(String id, CallbackInfoReturnable<String> cir) {
        String patched = VoxyLightBridge.patch(cir.getReturnValue());
        if (patched != null)
            cir.setReturnValue(patched);
    }
}
