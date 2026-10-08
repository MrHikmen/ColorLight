package me.mrhikmen.colorlight.client.mixin.sodium;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code setupTerrain} runs once per frame before any chunk layer is drawn - outside of a render pass, which is
 * where the light volume's per-frame header may be written. The camera is split exactly like Sodium splits it for its
 * own region offsets ({@link CameraTransform}), so the shader's position maths matches.
 */
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererMixin implements SodiumHookMarkers.Frame {

    @Inject(method = "setupTerrain", at = @At("HEAD"), require = 0)
    private void colorlight$frameStart(Camera camera,
                                       Viewport viewport,
                                       FogParameters fogParameters,
                                       boolean useOcclusionCulling,
                                       boolean updateChunksImmediately,
                                       Matrix4f cullMatrix,
                                       CallbackInfo ci) {
        Vec3 pos = camera.position();
        CameraTransform transform = new CameraTransform(pos.x(), pos.y(), pos.z());
        ColorLightGpu.onFrameStart(transform.intX, transform.intY, transform.intZ, transform.fracX, transform.fracY, transform.fracZ);
    }
}
