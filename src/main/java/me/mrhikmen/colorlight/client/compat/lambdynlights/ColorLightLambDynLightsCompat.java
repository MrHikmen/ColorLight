package me.mrhikmen.colorlight.client.compat.lambdynlights;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.render.gpu.ColorLightGpu;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;

/**
 * Switches LambDynamicLights off while ColorLight draws the light itself on the GPU.
 * <p>
 * LambDynamicLights' work (ticking the sources, spatial lookup, the lightmap injection, entity glows) is all gated by
 * its own dynamic lights mode. While ColorLight's GPU pipeline is active that work is wasted: ColorLight does not read
 * vanilla block light, so nothing LambDynamicLights computes reaches the picture. So for as long as that holds, the mode
 * is forced to {@code OFF}, and it is put back when it stops holding (config switch, shader pack, failure).
 * <p>
 * The mode is changed through reflection on the field, not through its setter: the setter also writes the mode into
 * the saved config, and the player's own choice must survive a restart.
 */
public final class ColorLightLambDynLightsCompat {

    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("lambdynlights");

    private static final String LDL_MAIN_CLASS = "dev.lambdaurora.lambdynlights.LambDynLights";

    private static boolean resolved;
    private static boolean resolveFailed;
    private static Object mod;
    private static Object config;
    private static Field modeField;
    private static Field refreshField;
    private static Object modeOff;

    /** True while ColorLight is holding LambDynamicLights off. */
    private static boolean forced;
    /** The mode the player had, to put back. */
    private static Object savedMode;
    private static boolean loggedFailure;

    public static boolean isPresent() {
        return PRESENT;
    }

    /** Called every client tick. Forces LambDynamicLights off or restores it, depending on the GPU pipeline. */
    public static void tick() {
        if (!PRESENT || resolveFailed || !resolve())
            return;

        try {
            boolean wanted = ColorLightClient.config.ENABLE && ColorLightClient.config.DISABLE_LDL_ON_GPU && ColorLightGpu.isActive();

            if (wanted && !forced) {
                Object current = modeField.get(config);
                if (current != modeOff) {
                    savedMode = current;
                    modeField.set(config, modeOff);
                    refreshField.setBoolean(mod, true); // rebuilds the chunks that had dynamic light
                    forced = true;
                    ColorLightClient.LOGGER.info("[ColorLight] GPU light pipeline is on: LambDynamicLights switched off");
                }
            } else if (!wanted && forced) {
                modeField.set(config, savedMode);
                refreshField.setBoolean(mod, true);
                forced = false;
                ColorLightClient.LOGGER.info("[ColorLight] GPU light pipeline is off: LambDynamicLights restored");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!loggedFailure) {
                loggedFailure = true;
                ColorLightClient.LOGGER.warn("[ColorLight] Could not switch LambDynamicLights off (further failures are not logged)", e);
            }
        }
    }

    private static boolean resolve() {
        if (resolved)
            return true;
        try {
            Class<?> main = Class.forName(LDL_MAIN_CLASS);
            Object instance = main.getMethod("get").invoke(null);
            if (instance == null)
                return false; // not created yet, try again next tick

            Field configRef = main.getField("config");
            Object cfg = configRef.get(instance);
            Field mode = cfg.getClass().getDeclaredField("dynamicLightsMode");
            mode.setAccessible(true);
            Field refresh = main.getDeclaredField("shouldForceRefresh");
            refresh.setAccessible(true);

            @SuppressWarnings({"unchecked", "rawtypes"})
            Object off = Enum.valueOf((Class) mode.getType(), "OFF");

            mod = instance;
            config = cfg;
            modeField = mode;
            refreshField = refresh;
            modeOff = off;
            resolved = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            resolveFailed = true;
            ColorLightClient.LOGGER.warn("[ColorLight] LambDynamicLights internals not found; it will keep running on the GPU pipeline", e);
            return false;
        }
    }

    private ColorLightLambDynLightsCompat() {
    }
}
