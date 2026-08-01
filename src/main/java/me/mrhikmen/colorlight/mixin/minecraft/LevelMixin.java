package me.mrhikmen.colorlight.mixin.minecraft;

import me.mrhikmen.colorlight.config.BlockSettings;
import me.mrhikmen.colorlight.light.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.light.ColorLightEngine;
import me.mrhikmen.colorlight.light.ColorLightEngineHolder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("RETURN")
    )
    private void colorlight$onSetBlock(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {

        if (!cir.getReturnValueZ())
            return;

        if (!((Object) this instanceof ClientLevel))
            return;

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return;

        Block newBlock = state.getBlock();
        BlockSettings entry = ColorLightBlockRegistry.get(newBlock);

        if (entry != null) {
            engine.addSource(pos, entry.r, entry.g, entry.b, entry.light);
        } else if (engine.hasSource(pos)) {
            engine.removeSource(pos);
        } else {
            engine.onBlockChanged(pos);
        }

        int radius = engine.getMaxRangeBlocks() + 1;
        Minecraft.getInstance().levelRenderer.setBlocksDirty(
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius
        );
    }
}