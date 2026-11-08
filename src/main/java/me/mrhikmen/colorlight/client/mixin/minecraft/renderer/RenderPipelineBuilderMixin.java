package me.mrhikmen.colorlight.client.mixin.minecraft.renderer;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;

import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Gives every entity, item and particle pipeline the light volume's bind group. Done when the pipeline is built, so
 * the bind group is part of the pipeline's layout whatever the mode is (see {@link ColorLightGpu#ENTITY_LIGHT_GROUP}).
 * {@code require = 0}: without it those pipelines are simply the vanilla ones.
 */
@Mixin(RenderPipeline.Builder.class)
public abstract class RenderPipelineBuilderMixin {

    @Shadow
    private Map<ShaderType, Identifier> shaders;

    @Shadow
    public abstract RenderPipeline.Builder withBindGroupLayout(BindGroupLayout bindGroupLayout);

    @Inject(method = "build", at = @At("HEAD"), require = 0)
    private void colorlight$addLightGroup(CallbackInfoReturnable<RenderPipeline> cir) {
        Identifier vertex = this.shaders.get(ShaderType.VERTEX);
        if (vertex != null && ColorLightGpu.isEntityShader(vertex)) {
            this.withBindGroupLayout(ColorLightGpu.ENTITY_LIGHT_GROUP);
        }
    }
}
