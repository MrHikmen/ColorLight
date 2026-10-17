package me.mrhikmen.colorlight.client.compat.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import me.mrhikmen.colorlight.client.compat.sable.ColorLightSableBridge;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Wraps Sable's draw of one chunked sub-level layer: when the GPU light pipeline is on, the layer is drawn with
 * ColorLight's copy of the core shader instead of the vanilla one. Targets Sable by name so ColorLight does not need
 * Sable to compile; {@code require = 0} semantics come from the mixin config.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData", remap = false)
public abstract class VanillaChunkedSubLevelRenderDataMixin {

    @WrapMethod(method = "renderChunkedSubLevel", remap = false)
    private void colorlight$lightSubLevel(RenderType layer, ShaderInstance shader, Matrix4f modelView,
                                          double camX, double camY, double camZ, Operation<Void> original) {
        ShaderInstance ours = null;
        try {
            ours = ColorLightSableBridge.prepare(this, shader, modelView, camX, camY, camZ);
        } catch (Throwable t) {
            ColorLightSableBridge.failed(t);
        }

        if (ours == null) {
            original.call(layer, shader, modelView, camX, camY, camZ);
            return;
        }

        try {
            original.call(layer, ours, modelView, camX, camY, camZ);
        } finally {
            ours.clear();
            ColorLightSableBridge.restoreTerrainBindings();
        }
    }
}
