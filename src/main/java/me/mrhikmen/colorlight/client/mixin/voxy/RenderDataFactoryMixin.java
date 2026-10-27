package me.mrhikmen.colorlight.client.mixin.voxy;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.world.WorldSection;

import me.mrhikmen.colorlight.client.compat.lod.voxy.LodSplit;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells {@link LodSplit} which section the mesher thread is working on, so {@link MesherMixin} knows where the
 * light sources are around the quads it gets.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderDataFactory", remap = false)
public abstract class RenderDataFactoryMixin {

    @Inject(method = "generateMesh", at = @At("HEAD"), remap = false, require = 0)
    private void colorlight$sectionStart(WorldSection section, CallbackInfoReturnable<BuiltSection> cir) {
        LodSplit.begin(section.lvl, section.x, section.y, section.z);
    }

    @Inject(method = "generateMesh", at = @At("RETURN"), remap = false, require = 0)
    private void colorlight$sectionEnd(WorldSection section, CallbackInfoReturnable<BuiltSection> cir) {
        LodSplit.end();
    }
}
