package me.mrhikmen.colorlight.client.compat.sable;

import me.mrhikmen.colorlight.client.ColorLightClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

import org.lwjgl.opengl.GL45C;

import java.util.Arrays;
import java.util.List;

/**
 * Flywheel objects on Sable sub-levels (block entities, Create contraptions) are drawn in an embedding that carries
 * Sable's <i>lighting scene id</i> of the sub-level. This class publishes, per scene id, which window of
 * {@link SableLightVolume} belongs to it, in a small GL buffer the Flywheel fragment shader reads
 * ({@code flw_apply_sable.glsl}, {@code u_CLScenes}). Read through reflection like the rest of the Sable compat.
 * <p>
 * Layout: {@value #STRIDE} ints per scene id: window anchor block (x, y, z), first page, plot-space x / z of scene
 * coordinate 0 (the plot's centre chunk's min corner), valid flag.
 */
public final class SableFlywheelScenes {

    private static final int STRIDE = 8;
    private static final int MAX_SCENES = 256;
    private static final double MAX_DISTANCE = 160.0;

    private static final int[] table = new int[MAX_SCENES * STRIDE];
    private static final int[] last = new int[MAX_SCENES * STRIDE];
    private static int buffer;
    private static boolean dirty = true;
    private static boolean broken;

    /** GL name of the scene table (always valid once called). */
    public static int buffer() {
        if (buffer == 0) {
            buffer = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(buffer, (long) table.length * Integer.BYTES, GL45C.GL_DYNAMIC_STORAGE_BIT);
            GL45C.glNamedBufferSubData(buffer, 0L, table);
        }
        return buffer;
    }

    /** Once per frame, before Flywheel draws: acquires the windows of nearby sub-levels and refreshes the table. */
    public static void update() {
        if (broken)
            return;
        try {
            buffer();
            Arrays.fill(table, 0);

            ClientLevel level = Minecraft.getInstance().level;
            Minecraft mc = Minecraft.getInstance();
            if (level != null && mc.gameRenderer != null && mc.gameRenderer.getMainCamera().isInitialized()) {
                var cam = mc.gameRenderer.getMainCamera().getPosition();
                List<SableBodies.Body> bodies = SableBodies.list(level);
                for (SableBodies.Body b : bodies) {
                    if (b.distSqToBounds(cam.x, cam.y, cam.z) > MAX_DISTANCE * MAX_DISTANCE)
                        continue;

                    int scene = (int) SableAccess.call(b.subLevel(), "getLightingSceneId");
                    if (scene <= 0 || scene >= MAX_SCENES)
                        continue; // no lighting scene: nothing of it is drawn by Flywheel

                    SableLightVolume.Window w = SableLightVolume.acquire(b.subLevel(),
                            ((b.pMinX() + b.pMaxX()) >> 1) >> 4,
                            ((b.pMinY() + b.pMaxY()) >> 1) >> 4,
                            ((b.pMinZ() + b.pMaxZ()) >> 1) >> 4);
                    if (w == null)
                        continue;

                    Object plot = SableAccess.call(b.subLevel(), "getPlot");
                    ChunkPos centre = (ChunkPos) SableAccess.call(plot, "getCenterChunk");

                    int o = scene * STRIDE;
                    table[o] = w.ax << 4;
                    table[o + 1] = w.ay << 4;
                    table[o + 2] = w.az << 4;
                    table[o + 3] = w.pageBase();
                    table[o + 4] = centre.getMinBlockX();
                    table[o + 5] = centre.getMinBlockZ();
                    table[o + 6] = 1;
                }
            }

            if (dirty || !Arrays.equals(table, last)) {
                dirty = false;
                System.arraycopy(table, 0, last, 0, table.length);
                GL45C.glNamedBufferSubData(buffer, 0L, table);
            }
        } catch (Throwable t) {
            broken = true;
            ColorLightClient.LOGGER.warn("[ColorLight] Flywheel objects on Sable sub-levels: Sable's classes are not what ColorLight expects; they keep Flywheel's own light", t);
        }
    }

    private SableFlywheelScenes() {
    }
}
