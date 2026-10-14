package me.mrhikmen.colorlight.client.compat.lod.voxy.mixin;

import me.mrhikmen.colorlight.client.compat.lod.voxy.VoxyVkLight;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds the far-light SSBO to the push descriptors of Voxy's terrain draws. Those are recognised by their bindings:
 * only the terrain pass writes the light sampler (9) and the depth sampler (10).
 */
@Mixin(targets = "me.cortex.voxy.client.core.vk.VkShaderPipeline$Binder")
public abstract class VkBinderMixin {

    @Shadow @Final private MemoryStack stack;
    @Shadow @Final private List<VkWriteDescriptorSet> writes;

    @Inject(method = "push", at = @At("HEAD"), require = 0)
    private void colorlight$addLightBuffer(CallbackInfo ci) {
        long handle = VoxyVkLight.currentHandle();
        if (handle == 0L)
            return;

        boolean light = false, depth = false;
        for (VkWriteDescriptorSet w : this.writes) {
            int b = w.dstBinding();
            if (b == VoxyVkLight.BINDING)
                return; // already there
            if (b == 9) light = true;
            if (b == 10) depth = true;
        }
        if (!light || !depth)
            return;

        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, this.stack)
                .buffer(handle).offset(0L).range(VoxyVkLight.currentSize());
        this.writes.add(VkWriteDescriptorSet.calloc(this.stack).sType$Default()
                .dstBinding(VoxyVkLight.BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(info));
    }
}
