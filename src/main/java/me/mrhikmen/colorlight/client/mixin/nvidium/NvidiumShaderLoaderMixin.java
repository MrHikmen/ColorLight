package me.mrhikmen.colorlight.client.mixin.nvidium;

import me.mrhikmen.colorlight.client.compat.nvidium.NvidiumBridge;

import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nvidium builds its terrain shaders from text ({@code ShaderLoader.parse}); the result is patched here so the mesh and
 * fragment shaders read ColorLight's light volume. Everything is {@code require = 0}: if Nvidium changed so that this
 * does not match, Nvidium keeps rendering with the vanilla light.
 */
@Mixin(targets = "me.cortex.nvidium.sodiumCompat.ShaderLoader", remap = false)
public abstract class NvidiumShaderLoaderMixin {

    @Inject(method = "parse(Lnet/minecraft/resources/Identifier;Ljava/util/function/Consumer;)Ljava/lang/String;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void colorlight$patch(Identifier path, Consumer<?> builder,
                                         CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(NvidiumBridge.patchShader(path.getPath(), cir.getReturnValue()));
    }
}
