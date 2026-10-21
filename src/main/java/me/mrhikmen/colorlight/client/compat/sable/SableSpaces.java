package me.mrhikmen.colorlight.client.compat.sable;

import net.minecraft.client.multiplayer.ClientLevel;

import org.joml.Vector3d;

import java.util.List;

/** Small public window onto the Sable sub-levels for other compats (Create contraptions inside a sub-level). */
public final class SableSpaces {

    private static final boolean SABLE = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sable");

    /** How far outside a plot's block box a contraption entity may be and still count as being in that plot. */
    private static final int ENTITY_PAD = 24;

    private static long cachedTick = Long.MIN_VALUE;
    private static ClientLevel cachedLevel;
    private static List<SableBodies.Body> cached = List.of();

    private static List<SableBodies.Body> bodies(ClientLevel level) {
        long now = level.getGameTime();
        if (now != cachedTick || level != cachedLevel) { // (a new level can start at the same game time)
            cachedTick = now;
            cachedLevel = level;
            cached = SableBodies.list(level);
        }
        return cached;
    }

    /**
     * The sub-level whose plot space holds an entity-like point: inside its block box first, else within
     * {@link #ENTITY_PAD} blocks of it (parts of a contraption that stick out of the placed blocks).
     */
    private static SableBodies.Body plotBodyOf(ClientLevel level, double x, double y, double z) {
        List<SableBodies.Body> all = bodies(level);
        for (SableBodies.Body b : all) {
            if (b.inPlot((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)))
                return b;
        }
        for (SableBodies.Body b : all) {
            if (b.inPlotPadded(x, y, z, ENTITY_PAD))
                return b;
        }
        return null;
    }

    /**
     * If the point lies inside the plot of a sub-level, writes its current world-space position to {@code out} and
     * returns true; false for ordinary world space (and without Sable).
     */
    public static boolean plotToWorld(ClientLevel level, double x, double y, double z, Vector3d out) {
        if (!SABLE)
            return false;
        SableBodies.Body b = plotBodyOf(level, x, y, z);
        if (b == null)
            return false;
        b.toWorld(x, y, z, out);
        return true;
    }

    /** Receives a point already mapped into the plot space of one sub-level. */
    public interface PlotVisitor {
        void visit(java.util.UUID subLevel, double plotX, double plotY, double plotZ);
    }

    /** The sub-level whose plot contains the point, or null (ordinary world space, or without Sable). */
    public static java.util.UUID plotOwner(ClientLevel level, double x, double y, double z) {
        if (!SABLE)
            return null;
        SableBodies.Body b = plotBodyOf(level, x, y, z);
        return b == null ? null : b.id();
    }

    /**
     * For every sub-level except {@code exclude} whose bounding box is within {@code reach} blocks of the world-space
     * point, calls {@code visitor} with the point mapped into that sub-level's plot space.
     */
    public static void forEachSubLevelInReach(ClientLevel level, double wx, double wy, double wz, double reach,
                                              java.util.UUID exclude, PlotVisitor visitor) {
        if (!SABLE)
            return;
        double reachSq = reach * reach;
        Vector3d scratch = new Vector3d();
        for (SableBodies.Body b : bodies(level)) {
            if (b.id().equals(exclude) || b.distSqToBounds(wx, wy, wz) > reachSq)
                continue;
            b.toPlot(wx, wy, wz, scratch);
            visitor.visit(b.id(), scratch.x, scratch.y, scratch.z);
        }
    }

    private SableSpaces() {
    }
}
