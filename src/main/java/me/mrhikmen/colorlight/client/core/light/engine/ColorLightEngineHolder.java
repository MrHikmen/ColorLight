package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;

public final class ColorLightEngineHolder {

    private static volatile ColorLightEngine engine;

    private static int maxRangeBlocks = 15;
    private static Identifier defaultMethod = PropagationMethodRegistry.GRID;
    private static Identifier dynamicMethod = PropagationMethodRegistry.SMOOTH;

    public static void configure(int maxRangeBlocks) {
        configure(maxRangeBlocks, defaultMethod, dynamicMethod);
    }

    /**
     * @param defaultMethod method plain blocks spread with; {@code null} or unknown ids mean {@code colorlight:grid}
     * @param dynamicMethod method moving lights spread with; {@code null} means {@code colorlight:smooth}
     */
    public static void configure(int maxRangeBlocks, Identifier defaultMethod, Identifier dynamicMethod) {
        ColorLightEngineHolder.maxRangeBlocks = Math.max(1, maxRangeBlocks);
        ColorLightEngineHolder.defaultMethod = (defaultMethod != null) ? defaultMethod : PropagationMethodRegistry.GRID;
        ColorLightEngineHolder.dynamicMethod = (dynamicMethod != null) ? dynamicMethod : PropagationMethodRegistry.SMOOTH;
    }

    /** Same as {@link #configure(int, Identifier, Identifier)} with the ids taken from config strings (legacy GRID / SMOOTH accepted). */
    public static void configure(int maxRangeBlocks, String defaultMethod, String dynamicMethod) {
        configure(maxRangeBlocks, PropagationMethodRegistry.parse(defaultMethod), PropagationMethodRegistry.parse(dynamicMethod));
    }

    public static void set(ClientLevel level) {
        if (level == null) {
            engine = null;
            return;
        }
        engine = new ColorLightEngine(level, maxRangeBlocks, defaultMethod, dynamicMethod);
    }

    public static ColorLightEngine get() {
        return engine;
    }

    public static int getMaxRangeBlocks() {
        return maxRangeBlocks;
    }

    public static Identifier getDefaultMethod() {
        return defaultMethod;
    }

    public static Identifier getDynamicMethod() {
        return dynamicMethod;
    }

    public static void tick() {

    }
}
