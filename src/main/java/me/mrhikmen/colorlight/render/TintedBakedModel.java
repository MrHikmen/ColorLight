package me.mrhikmen.colorlight.render;

import me.mrhikmen.colorlight.light.ColorLightEngine;
import me.mrhikmen.colorlight.light.ColorLightEngineHolder;
import me.mrhikmen.colorlight.light.ColorLightUtil;

import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

public class TintedBakedModel extends ForwardingBakedModel {

    public TintedBakedModel(BakedModel original) {
        this.wrapped = original;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos,
                               Supplier<RandomSource> randomSupplier, RenderContext context) {

        ColorLightEngine engine = ColorLightEngineHolder.get();

        if (engine == null || engine.hasSource(pos)) {
            super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
            return;
        }

        context.pushTransform(quad -> {

            Direction face = quad.lightFace();
            BlockPos daylightPos = (face != null) ? pos.relative(face) : pos;
            float daylight = engine.getDaylightFactor(daylightPos);

            for (int i = 0; i < 4; i++) {
                int packed = SmoothLightSampler.sample(engine, pos, face, quad.x(i), quad.y(i), quad.z(i));
                quad.color(i, ColorLightUtil.toArgb(packed, daylight));
            }

            return true;
        });

        super.emitBlockQuads(blockView, state, pos, randomSupplier, context);

        context.popTransform();
    }
}