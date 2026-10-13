package me.mrhikmen.colorlight.client.compat.distanthorizons.mixin;

import me.mrhikmen.colorlight.client.compat.distanthorizons.DhLightBridge;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons turns every shader file into source text in {@code GlShader.loadFile(path, absoluteFilePath)}
 * (read from its own jar via the classloader, so a resource pack can't replace them). We look at the path and
 * change the terrain fragment shader only ({@code assets/distanthorizons/shaders/terrain/gl/frag.frag}).
 * <p>
 * Targeted by name so that ColorLight needs no Distant Horizons at compile time beyond the dev-only API jar;
 * {@code ColorLightDistantHorizonsPlugin} keeps this out when Distant Horizons isn't installed.
 */
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader")
public abstract class GlShaderMixin {

    @Inject(method = "loadFile", at = @At("RETURN"), cancellable = true, require = 0)
    private static void colorlight$addFarLight(String path, boolean absoluteFilePath, CallbackInfoReturnable<String> cir) {
        String patched = DhLightBridge.patch(path, cir.getReturnValue());
        if (patched != null)
            cir.setReturnValue(patched);
    }
}
