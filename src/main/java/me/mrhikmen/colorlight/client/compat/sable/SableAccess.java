package me.mrhikmen.colorlight.client.compat.sable;

import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads what ColorLight needs from Sable's render data through reflection, so ColorLight compiles and loads without
 * Sable on the class path and does not break on Sable updates (a failure just disables the sub-level light, see
 * {@link #read}).
 */
final class SableAccess {

    /** Snapshot of one sub-level as VanillaChunkedSubLevelRenderData draws it. */
    record Info(Object subLevel, Vector3dc position, Quaterniondc orientation, Vector3dc rotationPoint, Vector3dc origin,
                int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    private static final ConcurrentHashMap<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();
    private static volatile boolean broken;

    static Info read(Object renderData) {
        if (broken)
            return null;
        try {
            Object subLevel = field(renderData, "subLevel").get(renderData);
            Vector3dc origin = (Vector3dc) field(renderData, "origin").get(renderData);

            Object pose = call(subLevel, "renderPose");
            Object plot = call(subLevel, "getPlot");
            Object box = call(plot, "getBoundingBox");
            if (pose == null || box == null)
                return null;

            int minX = (int) call(box, "minX"), minY = (int) call(box, "minY"), minZ = (int) call(box, "minZ");
            int maxX = (int) call(box, "maxX"), maxY = (int) call(box, "maxY"), maxZ = (int) call(box, "maxZ");
            if (minX > maxX || minY > maxY || minZ > maxZ)
                return null; // empty

            return new Info(subLevel,
                    (Vector3dc) call(pose, "position"),
                    (Quaterniondc) call(pose, "orientation"),
                    (Vector3dc) call(pose, "rotationPoint"),
                    origin, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (Throwable t) {
            broken = true;
            me.mrhikmen.colorlight.client.ColorLightClient.LOGGER.warn("[ColorLight] Sable's render classes are not what ColorLight expects; light on sub-levels is off", t);
            return null;
        }
    }

    private static Field field(Object target, String name) throws Exception {
        Class<?> cls = target.getClass();
        Object cached = CACHE.computeIfAbsent("f:" + cls.getName() + "#" + name, k -> {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            return MISSING;
        });
        if (cached == MISSING)
            throw new NoSuchFieldException(cls.getName() + "." + name);
        return (Field) cached;
    }

    private static Object call(Object target, String name) throws Exception {
        Class<?> cls = target.getClass();
        Object cached = CACHE.computeIfAbsent("m:" + cls.getName() + "#" + name, k -> {
            try {
                Method m = cls.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (ReflectiveOperationException e) {
                return MISSING;
            }
        });
        if (cached == MISSING)
            throw new NoSuchMethodException(cls.getName() + "." + name + "()");
        return ((Method) cached).invoke(target);
    }

    private SableAccess() {
    }
}
