package me.mrhikmen.colorlight.client.mixin.flywheel;

import me.mrhikmen.colorlight.client.compat.flywheel.ColorLightFlywheelCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Flywheel parses every shader file through {@code SourceFile.parse(finder, name, text)}; the text of its light shaders
 * and of common.frag is patched here (see {@link ColorLightFlywheelCompat}). {@code require = 0}: if Flywheel changed so
 * that this does not apply, Flywheel keeps rendering with its own light.
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.flywheel.backend.glsl.SourceFile", remap = false)
public abstract class SourceFileMixin {

    @ModifyVariable(method = "parse", at = @At("HEAD"), argsOnly = true, require = 0, remap = false)
    private static String colorlight$patch(String source) {
        return ColorLightFlywheelCompat.patch(source);
    }
}
