package me.mrhikmen.colorlight.client.compat.sable;

import me.mrhikmen.colorlight.client.ColorLightClient;

import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;

/** Public entry point for the Sable mixin (the compat class itself keeps its internals package-private). */
public final class ColorLightSableBridge {

    private static boolean reported;

    public static ShaderInstance prepare(Object renderData, ShaderInstance original, Matrix4f modelView, double camX, double camY, double camZ) {
        return ColorLightSableCompat.prepare(renderData, original, modelView, camX, camY, camZ);
    }

    /** The sub-level draw used texture units 5..7 for its own buffers; give them back to the terrain. */
    public static void restoreTerrainBindings() {
        me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu.rebindMainUnits();
    }

    public static void failed(Throwable t) {
        if (!reported) {
            reported = true;
            ColorLightClient.LOGGER.warn("[ColorLight] Sable sub-level lighting failed; drawing with Sable's own shader", t);
        }
    }

    private ColorLightSableBridge() {
    }
}
