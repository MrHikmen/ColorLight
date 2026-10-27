package me.mrhikmen.colorlight.client.mixin.flywheel;

import me.mrhikmen.colorlight.client.compat.flywheel.ColorLightFlywheelCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The light volume is bound to texture units 10..12 for the duration of Flywheel's draws. */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.flywheel.backend.engine.EngineImpl", remap = false)
public abstract class EngineImplMixin {

    @Inject(method = {"render", "renderCrumbling"}, at = @At("HEAD"), require = 0, remap = false)
    private void colorlight$bind(CallbackInfo ci) {
        ColorLightFlywheelCompat.bind();
    }

    @Inject(method = {"render", "renderCrumbling"}, at = @At("RETURN"), require = 0, remap = false)
    private void colorlight$unbind(CallbackInfo ci) {
        ColorLightFlywheelCompat.unbind();
    }
}
