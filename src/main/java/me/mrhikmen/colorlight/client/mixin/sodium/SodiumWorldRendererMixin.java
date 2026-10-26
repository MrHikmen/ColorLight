package me.mrhikmen.colorlight.client.mixin.sodium;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;
import me.mrhikmen.colorlight.client.core.render.gpu.SodiumHookMarkers;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code setupTerrain} runs once per frame before any chunk layer is drawn - outside of a render pass, which is
 * where the light volume's per-frame header may be written. The camera is split exactly like Sodium splits it for its
 * own region offsets ({@link CameraTransform}), so the shader's position maths matches.
 * <p>
 * The handler takes no target arguments (Sodium's parameter list differs between versions); the camera is read from
 * the game's main camera, which is the one Sodium is given.
 */
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererMixin implements SodiumHookMarkers.Frame {

    @Inject(method = "setupTerrain", at = @At("HEAD"), require = 0)
    private void colorlight$frameStart(CallbackInfo ci) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 pos = camera.position();
        CameraTransform transform = new CameraTransform(pos.x(), pos.y(), pos.z());
        ColorLightGpu.onFrameStart(transform.intX, transform.intY, transform.intZ, transform.fracX, transform.fracY, transform.fracZ);
    }
}
