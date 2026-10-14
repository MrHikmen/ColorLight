package me.mrhikmen.colorlight.client.compat.lod.voxy.mixin;

import me.mrhikmen.colorlight.client.compat.lod.voxy.VoxyLightBridge;
import me.mrhikmen.colorlight.client.compat.lod.voxy.VoxyVkLight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Vulkan build of Voxy: its terrain draws use an explicit descriptor table, so the far-light buffer has to be added to
 * it, filled before the draw and released with the renderer. Targeted by name (no compile-time Voxy dependency).
 */
@Mixin(targets = "me.cortex.voxy.client.core.vk.render.VkTerrainRenderer")
public abstract class VkTerrainRendererMixin {

    /** The terrain pipelines' binding table is the only 8-argument List.of in ensureTerrainPipelines. */
    @Redirect(method = "ensureTerrainPipelines", require = 0,
            at = @At(value = "INVOKE", target = "Ljava/util/List;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                    + "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"))
    private List<Object> colorlight$addLightBinding(Object a, Object b, Object c, Object d, Object e, Object f, Object g, Object h) {
        List<Object> out = new ArrayList<>(List.of(a, b, c, d, e, f, g, h));
        if (VoxyLightBridge.isPatchedForVulkan())
            out.add(VoxyVkLight.newSsboBinding());
        return out;
    }

    @Inject(method = "renderTerrain", at = @At("HEAD"), require = 0)
    private void colorlight$prepareLightBuffer(CallbackInfo ci) {
        VoxyVkLight.beginDraw(this);
    }

    @Inject(method = "free", at = @At("HEAD"), require = 0)
    private void colorlight$releaseLightBuffer(CallbackInfo ci) {
        VoxyVkLight.onRendererFree();
    }
}
