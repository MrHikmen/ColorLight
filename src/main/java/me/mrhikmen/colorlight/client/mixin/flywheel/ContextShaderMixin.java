package me.mrhikmen.colorlight.client.mixin.flywheel;

import me.mrhikmen.colorlight.client.compat.flywheel.ColorLightFlywheelCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs while Flywheel links a pipeline program (it is bound then): binds the volume samplers to their units. */
@Pseudo
@Mixin(targets = {"com.zurrtum.create.client.flywheel.backend.compile.ContextShader", "dev.engine_room.flywheel.backend.compile.ContextShader"}, remap = false)
public abstract class ContextShaderMixin {

    @Inject(method = "onLink", at = @At("HEAD"), require = 0, remap = false)
    private void colorlight$bindSamplers(CallbackInfo ci) {
        ColorLightFlywheelCompat.onLink();
    }
}
