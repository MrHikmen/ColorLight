package me.mrhikmen.colorlight.client.mixin.voxy;

import me.mrhikmen.colorlight.client.compat.lod.voxy.LodSplit;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy's mesher hands every finished (merged) quad to {@code emitQuad}. Where a coloured light reaches, the quad is
 * emitted as single cells instead (see {@link LodSplit}), so the shader can colour each block on its own.
 * <p>
 * {@code x, z} are the far corner of the quad and {@code length, width} its size; the original method counts back from
 * there, so cell (x - i, z - j) for i < length, j < width are exactly the cells of the quad.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderDataFactory$Mesher", remap = false)
public abstract class MesherMixin {

    @Shadow(remap = false)
    public int auxiliaryPosition;

    @Shadow(remap = false)
    public int axis;

    @Shadow(remap = false)
    protected abstract void emitQuad(int x, int z, int length, int width, long data);

    @Unique
    private boolean colorlight$splitting;

    @Inject(method = "emitQuad", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void colorlight$splitNearLight(int x, int z, int length, int width, long data, CallbackInfo ci) {
        if (this.colorlight$splitting || (length == 1 && width == 1))
            return;
        if (!LodSplit.wants(this.axis, this.auxiliaryPosition, x - (length - 1), x, z - (width - 1), z))
            return;

        ci.cancel();
        this.colorlight$splitting = true;
        try {
            for (int i = 0; i < length; i++)
                for (int j = 0; j < width; j++)
                    this.emitQuad(x - i, z - j, 1, 1, data);
        } finally {
            this.colorlight$splitting = false;
        }
    }
}
