package me.mrhikmen.colorlight.client.mixin.voxy;

import me.mrhikmen.colorlight.client.compat.lod.voxy.LodLight;
import me.mrhikmen.colorlight.client.compat.lod.voxy.VoxyRemesh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy queues a section for meshing whenever its data is new or changed. We use that only as a hint that the light
 * sources in that section's column may have changed (see {@code VoxyLightCache#onVoxySection}).
 * <p>
 * Targeted by name so that ColorLight needs no Voxy at compile time; Mixin skips it when
 * Voxy isn't installed, and {@code require = 0} means a Voxy that changed this method only costs the hint (light then
 * still comes from the periodic scan around the player).
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderGenerationService")
public abstract class RenderGenerationServiceMixin {

    @Inject(method = "enqueueTask(J)V", at = @At("HEAD"), require = 0)
    private void colorlight$sectionQueued(long pos, CallbackInfo ci) {
        VoxyRemesh.remember(this);
        if (!VoxyRemesh.isInternal())
            LodLight.onVoxySection(pos);
    }
}
