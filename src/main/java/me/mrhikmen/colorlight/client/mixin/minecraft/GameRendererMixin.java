package me.mrhikmen.colorlight.client.mixin.minecraft;

//import me.mrhikmen.colorlight.client.compat.lod.LodLightOverlayRenderer;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "close", at = @At("RETURN"))
    private void colorlight$onGameRendererClose(CallbackInfo ci) {
//        LodLightOverlayRenderer.close();
    }
}
