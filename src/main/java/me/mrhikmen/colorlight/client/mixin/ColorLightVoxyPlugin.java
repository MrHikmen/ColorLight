package me.mrhikmen.colorlight.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Gate for {@code colorlight.voxy.mixins.json}: only applied when Voxy is installed and is a version the LOD shader
 * patch was written against. {@code -Dcolorlight.noVoxy=true} switches it off, {@code -Dcolorlight.forceVoxy=true}
 * applies it to any Voxy version (the shader patch still checks its anchors before changing anything).
 */
public final class ColorLightVoxyPlugin implements IMixinConfigPlugin {

    private static final String SUPPORTED_VOXY_PREFIX = "0.2.";

    private boolean apply;

    @Override
    public void onLoad(String mixinPackage) {
        apply = decide();
    }

    private static boolean decide() {
        if (Boolean.getBoolean("colorlight.noVoxy"))
            return false;

        Optional<ModContainer> voxy = FabricLoader.getInstance().getModContainer("voxy");
        if (voxy.isEmpty())
            return false;

        if (Boolean.getBoolean("colorlight.forceVoxy"))
            return true;

        return voxy.get().getMetadata().getVersion().getFriendlyString().startsWith(SUPPORTED_VOXY_PREFIX);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return apply;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
