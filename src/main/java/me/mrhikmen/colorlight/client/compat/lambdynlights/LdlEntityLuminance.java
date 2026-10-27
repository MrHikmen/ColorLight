package me.mrhikmen.colorlight.client.compat.lambdynlights;

import me.mrhikmen.colorlight.client.ColorLightClient;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * How bright LambDynamicLights itself thinks an entity is (0-15).
 * <p>
 * ColorLight only knows blocks: for an entity it looks at the block items in the hands, an item frame, a dropped item
 * or a minecart's block. Anything else LambDynamicLights lights up - a belt lantern from Trinkets, a glow squid, a
 * burning mob, a blaze - is invisible to ColorLight. That is harmless on the CPU path (vanilla's light is still there
 * under ColorLight's tint), but on the GPU path ColorLight draws all block light, so such an entity gave no light at
 * all. {@link ColorLightEntityLightTicker} uses this value to give those entities a plain white light instead.
 * <p>
 * The accessor is not part of LambDynamicLights' API artifact, so it is read reflectively. LambDynamicLights mixes an
 * interface with {@code int getLuminance()} into {@link Entity} ({@code EntityDynamicLightSource} in 4.x). After the
 * mixin is applied the method is declared by {@code Entity} itself, so it cannot be found by looking at where the
 * method is declared; it is found through the LambDynamicLights <b>interface</b> that {@code Entity} implements and
 * called through that interface's method. If nothing is found, this reports 0 and ColorLight behaves as before.
 */
public final class LdlEntityLuminance {

    private static final String LDL_PACKAGE = "dev.lambdaurora.lambdynlights";

    private static boolean resolved;
    private static Method accessor;
    private static boolean loggedFailure;

    /** LambDynamicLights' current luminance for the entity, clamped to 0..15; 0 if unknown. */
    public static int of(Entity entity) {
        Method method = accessor();
        if (method == null)
            return 0;

        try {
            Object value = method.invoke(entity);
            return value instanceof Integer i ? Math.max(0, Math.min(15, i)) : 0;
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                ColorLightClient.LOGGER.warn("[ColorLight] Could not read LambDynamicLights entity luminance (further failures are not logged)", t);
            }
            return 0;
        }
    }

    private static synchronized Method accessor() {
        if (!resolved) {
            resolved = true;
            accessor = find();
            if (accessor == null) {
                ColorLightClient.LOGGER.info("[ColorLight] LambDynamicLights entity luminance accessor not found; "
                        + "entities ColorLight does not know will not get a white light");
            } else {
                ColorLightClient.LOGGER.info("[ColorLight] LambDynamicLights entity luminance: {}#{}",
                        accessor.getDeclaringClass().getName(), accessor.getName());
            }
        }
        return accessor;
    }

    /** Walks every interface {@link Entity} implements (the LambDynamicLights mixin adds its own) for getLuminance(). */
    private static Method find() {
        Set<Class<?>> seen = new HashSet<>();
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        for (Class<?> type = Entity.class; type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces())
                queue.add(iface);
        }

        Method fallback = null;
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            if (!seen.add(iface))
                continue;
            for (Class<?> parent : iface.getInterfaces())
                queue.add(parent);

            if (!iface.getName().startsWith(LDL_PACKAGE))
                continue;

            for (Method method : iface.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() != int.class)
                    continue;

                String name = method.getName();
                if (name.equals("getLuminance"))
                    return prepare(method);
                if (fallback == null && name.toLowerCase(java.util.Locale.ROOT).contains("luminance")
                        && !name.toLowerCase(java.util.Locale.ROOT).contains("last"))
                    fallback = method;
            }
        }
        return fallback == null ? null : prepare(fallback);
    }

    private static Method prepare(Method method) {
        try {
            method.setAccessible(true);
        } catch (RuntimeException ignored) {
            // public interface method: calling it works without
        }
        return method;
    }

    private LdlEntityLuminance() {
    }
}
