package me.mrhikmen.colorlight.mixin.minecraft;

import me.mrhikmen.colorlight.config.BlockSettings;
import me.mrhikmen.colorlight.core.light.registry.ColorLightBlockRegistry;
import me.mrhikmen.colorlight.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.core.light.scan.ColorLightChunkScanner;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow
    private Level level;

    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN")
    )
    private void colorlight$onSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null)
            return;

        if (!(this.level instanceof ClientLevel))
            return;

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return;

        // The engine records exactly which render sections its change touched
        // (see ColorLightEngine#drainDirtySections), so nothing needs to be marked dirty by hand here.
        BlockSettings entry = ColorLightBlockRegistry.get(state.getBlock());
        int emission = (entry != null) ? state.getLightEmission() : 0;

        // Flags dynamic lights near the change for recomputation. Lock-free, so it stays on the game thread.
        engine.invalidateDynamicAround(pos);

        // Nothing lit nearby and not a source: the change cannot affect static light, so don't even queue it.
        // (Checked lock-free; this is the overwhelmingly common case.) Only trusted while no flood is running -
        // otherwise light that hasn't reached this block yet would later pass straight through it.
        if (emission <= 0 && ColorLightChunkScanner.isApplyIdle()
                && !engine.hasSource(pos) && !engine.hasStaticLightNear(pos))
            return;

        // The light update itself needs the engine lock, which the apply thread can hold for milliseconds while
        // flooding freshly loaded chunks. Do it there (FIFO with chunk loads/unloads) instead of stalling the frame.
        final BlockPos changed = pos.immutable();
        final BlockSettings source = (emission > 0) ? entry : null;
        final int strength = (source != null) ? Math.min(source.light, emission) : 0;

        ColorLightChunkScanner.runOnApplyThread(() -> {
            if (engine != ColorLightEngineHolder.get())
                return; // world changed meanwhile

            if (source != null) {
                // re-adding an identical source is a no-op, a changed one is cleaned up and re-flooded
                engine.addSource(changed, source.r, source.g, source.b, strength);
            } else if (engine.hasSource(changed)) {
                engine.removeSource(changed);
            } else {
                // a cheap early-out if no static light is nearby
                engine.onBlockChanged(changed);
            }
        });
    }
}
