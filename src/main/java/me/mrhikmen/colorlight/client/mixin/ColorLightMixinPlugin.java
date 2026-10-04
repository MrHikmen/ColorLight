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
 * Gate for {@code colorlight.sodium.mixins.json}: those mixins are tied to Sodium's internal shader and renderer code,
 * so they are only applied to a Sodium version they were written against. Anything else just leaves them out and
 * ColorLight keeps using vertex colours, which is version independent.
 * <p>
 * Escape hatches: {@code -Dcolorlight.noGpu=true} skips them entirely; {@code -Dcolorlight.forceGpu=true} applies them
 * to any Sodium version.
 */
public final class ColorLightMixinPlugin implements IMixinConfigPlugin {

    /** Sodium versions the GPU pipeline was written against: major.minor prefix plus the Minecraft version. */
    private static final String SUPPORTED_SODIUM_PREFIX = "0.9.";
    private static final String SUPPORTED_MINECRAFT_TAG = "mc26.3";

    private boolean apply;

    @Override
    public void onLoad(String mixinPackage) {
        apply = decide();
    }

    private static boolean decide() {
        if (Boolean.getBoolean("colorlight.noGpu"))
            return false;

        Optional<ModContainer> sodium = FabricLoader.getInstance().getModContainer("sodium");
        if (sodium.isEmpty())
            return false;

        if (Boolean.getBoolean("colorlight.forceGpu"))
            return true;

        String version = sodium.get().getMetadata().getVersion().getFriendlyString();
        return version.startsWith(SUPPORTED_SODIUM_PREFIX) && version.contains(SUPPORTED_MINECRAFT_TAG);
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
