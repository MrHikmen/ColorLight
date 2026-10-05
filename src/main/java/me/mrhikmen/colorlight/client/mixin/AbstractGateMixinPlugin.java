package me.mrhikmen.colorlight.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Shared base for ColorLight's {@link IMixinConfigPlugin}s. Both {@link ColorLightMixinPlugin} and
 * {@link ColorLightVoxyPlugin} are plain all-or-nothing gates: they decide once, when their mixin config loads,
 * whether their whole mixin set should be applied, and never touch the rest of the mixin pipeline (no per-mixin
 * filtering, no target rewriting). This base class carries that shared no-op behaviour so each gate only needs
 * to implement {@link #decide()}.
 */
abstract class AbstractGateMixinPlugin implements IMixinConfigPlugin {

    private boolean apply;

    @Override
    public final void onLoad(String mixinPackage) {
        apply = decide();
    }

    /** Whether this plugin's mixin config should be applied at all, decided once when the config is loaded. */
    protected abstract boolean decide();

    @Override
    public final String getRefMapperConfig() {
        return null;
    }

    @Override
    public final boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return apply;
    }

    @Override
    public final void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public final List<String> getMixins() {
        return null;
    }

    @Override
    public final void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public final void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
