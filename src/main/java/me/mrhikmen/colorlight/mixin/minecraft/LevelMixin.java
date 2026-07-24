package me.mrhikmen.colorlight.mixin.minecraft;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.light.ColorLightEngine;
import me.mrhikmen.colorlight.light.ColorLightEngineHolder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN")
    )
    private void colorlight$onSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {

        ColorLightClient.LOGGER.info("[ColorLight DEBUG] setBlock fired at " + pos.toShortString() + " result=" + cir.getReturnValueZ());

        if (!cir.getReturnValueZ())
            return;

        if (!((Object) this instanceof ClientLevel))
            return;

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return;

        engine.onBlockChanged(pos);

        int radius = ColorLightEngine.MAX_RANGE_BLOCKS + 1;
        Minecraft.getInstance().levelRenderer.setBlocksDirty(
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius
        );
    }
}