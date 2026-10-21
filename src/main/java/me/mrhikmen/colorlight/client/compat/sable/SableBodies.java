package me.mrhikmen.colorlight.client.compat.sable;

import me.mrhikmen.colorlight.client.ColorLightClient;

import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot of the Sable sub-levels of a level, read by reflection like {@link SableAccess} (ColorLight does not compile
 * against Sable). Used by {@link SableLightLinker} to move light between the world and a sub-level's plot space.
 * <p>
 * A {@link Body} copies the pose, so it stays valid (and cheap to use) for the rest of the tick it was read in.
 */
final class SableBodies {

    /**
     * One sub-level. {@code p*} is the plot's block bounding box (plot space), {@code g*} the global (world space)
     * bounding box. Plot space -> world space is the one Sable renders with:
     * {@code world = R * (S * (plot - rotationPoint)) + position}.
     */
    record Body(Object subLevel, UUID id,
                Vector3d position, Quaterniond orientation, Vector3d rotationPoint, Vector3d scale,
                int pMinX, int pMinY, int pMinZ, int pMaxX, int pMaxY, int pMaxZ,
                double gMinX, double gMinY, double gMinZ, double gMaxX, double gMaxY, double gMaxZ) {

        boolean inPlot(int x, int y, int z) {
            return x >= pMinX && x <= pMaxX && y >= pMinY && y <= pMaxY && z >= pMinZ && z <= pMaxZ;
        }

        /**
         * Like {@link #inPlot} for a point (an entity, a contraption) that is allowed to be up to {@code pad} blocks
         * outside the plot's block box: the box only covers placed blocks, and a piston, a gantry or a carriage of a
         * contraption mounted on the sub-level moves beyond it while still being in the sub-level's plot space.
         */
        boolean inPlotPadded(double x, double y, double z, int pad) {
            return x >= pMinX - pad && x < pMaxX + 1 + pad
                    && y >= pMinY - pad && y < pMaxY + 1 + pad
                    && z >= pMinZ - pad && z < pMaxZ + 1 + pad;
        }

        /** Squared distance from a world-space point to the sub-level's global bounding box (0 inside). */
        double distSqToBounds(double x, double y, double z) {
            double dx = Math.max(Math.max(gMinX - x, 0.0), x - gMaxX);
            double dy = Math.max(Math.max(gMinY - y, 0.0), y - gMaxY);
            double dz = Math.max(Math.max(gMinZ - z, 0.0), z - gMaxZ);
            return dx * dx + dy * dy + dz * dz;
        }

        /** Plot-space point -> world-space point (written to and returned as {@code out}). */
        Vector3d toWorld(double px, double py, double pz, Vector3d out) {
            out.set((px - rotationPoint.x) * scale.x, (py - rotationPoint.y) * scale.y, (pz - rotationPoint.z) * scale.z);
            orientation.transform(out);
            return out.add(position);
        }

        /** World-space point -> plot-space point (written to and returned as {@code out}). */
        Vector3d toPlot(double wx, double wy, double wz, Vector3d out) {
            out.set(wx - position.x, wy - position.y, wz - position.z);
            orientation.transformInverse(out);
            out.set(out.x / scale.x, out.y / scale.y, out.z / scale.z);
            return out.add(rotationPoint);
        }
    }

    private static Method getContainer, getAllSubLevels;
    private static volatile boolean broken;

    /** Every live sub-level of {@code level} (empty without Sable, without a container, or if Sable's classes changed). */
    static List<Body> list(Level level) {
        if (broken)
            return List.of();
        try {
            if (getContainer == null) {
                Class<?> container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                getAllSubLevels = container.getMethod("getAllSubLevels");
                getContainer = container.getMethod("getContainer", Level.class);
            }

            Object container = getContainer.invoke(null, level);
            if (container == null)
                return List.of();
            List<?> all = (List<?>) getAllSubLevels.invoke(container);
            if (all == null || all.isEmpty())
                return List.of();

            List<Body> bodies = new ArrayList<>(all.size());
            for (Object sub : all) {
                Body body = read(sub);
                if (body != null)
                    bodies.add(body);
            }
            return bodies;
        } catch (Throwable t) {
            broken = true;
            ColorLightClient.LOGGER.warn("[ColorLight] Sable's sub-level classes are not what ColorLight expects; light between the world and sub-levels is off", t);
            return List.of();
        }
    }

    private static Body read(Object sub) throws Exception {
        if ((boolean) SableAccess.call(sub, "isRemoved"))
            return null;
        UUID id = (UUID) SableAccess.call(sub, "getUniqueId");
        Object pose = SableAccess.call(sub, "logicalPose");
        Object plot = SableAccess.call(sub, "getPlot");
        Object pbox = plot == null ? null : SableAccess.call(plot, "getBoundingBox");
        Object gbox = SableAccess.call(sub, "boundingBox");
        if (id == null || pose == null || pbox == null || gbox == null)
            return null;

        int pMinX = (int) SableAccess.call(pbox, "minX"), pMinY = (int) SableAccess.call(pbox, "minY"), pMinZ = (int) SableAccess.call(pbox, "minZ");
        int pMaxX = (int) SableAccess.call(pbox, "maxX"), pMaxY = (int) SableAccess.call(pbox, "maxY"), pMaxZ = (int) SableAccess.call(pbox, "maxZ");
        if (pMinX > pMaxX || pMinY > pMaxY || pMinZ > pMaxZ)
            return null; // empty

        return new Body(sub, id,
                new Vector3d((org.joml.Vector3dc) SableAccess.call(pose, "position")),
                new Quaterniond((org.joml.Quaterniondc) SableAccess.call(pose, "orientation")),
                new Vector3d((org.joml.Vector3dc) SableAccess.call(pose, "rotationPoint")),
                new Vector3d((org.joml.Vector3dc) SableAccess.call(pose, "scale")),
                pMinX, pMinY, pMinZ, pMaxX, pMaxY, pMaxZ,
                (double) SableAccess.call(gbox, "minX"), (double) SableAccess.call(gbox, "minY"), (double) SableAccess.call(gbox, "minZ"),
                (double) SableAccess.call(gbox, "maxX"), (double) SableAccess.call(gbox, "maxY"), (double) SableAccess.call(gbox, "maxZ"));
    }

    private SableBodies() {
    }
}
