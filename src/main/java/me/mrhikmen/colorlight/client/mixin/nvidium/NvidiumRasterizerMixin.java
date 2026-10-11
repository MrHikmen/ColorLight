package me.mrhikmen.colorlight.client.mixin.nvidium;

import me.mrhikmen.colorlight.client.core.render.gpu.NvidiumBridge;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right before each terrain draw (primary, temporal, translucent / OIT) the light volume's buffers are attached to
 * the program Nvidium just bound.
 */
@Mixin(targets = {
        "me.cortex.nvidium.renderers.PrimaryTerrainRasterizer",
        "me.cortex.nvidium.renderers.TemporalTerrainRasterizer",
        "me.cortex.nvidium.renderers.TranslucentTerrainRasterizer"
}, remap = false)
public abstract class NvidiumRasterizerMixin {

    @Inject(method = "raster",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/NVMeshShader;glMultiDrawMeshTasksIndirectNV"),
            require = 0, remap = false)
    private void colorlight$bindLightVolume(CallbackInfo ci) {
        NvidiumBridge.bindCurrentProgram();
    }
}
