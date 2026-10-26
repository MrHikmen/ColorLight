package me.mrhikmen.colorlight.client.compat.lod.voxy;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.compat.lod.LodLightHub;
import me.mrhikmen.colorlight.client.compat.lod.LodLightStore;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps Voxy from merging the faces that a coloured light reaches into big quads.
 * <p>
 * Voxy merges neighbouring faces into one quad (up to 16x16 cells) when their block, biome and <i>vanilla</i> light value
 * are equal, and the LOD shader works out the colour of a quad once, for the whole quad. Where Voxy's own block light is
 * zero (everywhere outside the diamond of the vanilla light level of a source) the ground therefore became a few huge
 * quads, whose single colour was taken at their middle and ends: a coloured light was cut off along the diamond of the
 * vanilla light, whatever propagation method it used (a round or square light came out as a diamond).
 * <p>
 * The mesher is told here, per section, where light reaches ({@link #begin}); every quad it is about to emit that touches
 * such a place is then split into single cells ({@link #wants}), so the shader measures the light of every block and the
 * result is the same shape as near the player. Quads that no light reaches are untouched, so the extra quads exist only
 * around lights. Only the finest levels are split ({@link #MAX_LOD}): coarser cells are already as big as the light.
 * <p>
 * Called from Voxy's mesher threads (one state per thread). Contains no class of Voxy.
 */
public final class LodSplit {

    /** Finest levels (0 = full detail) whose quads are split. */
    public static final int MAX_LOD = 2;
    /** Most extra quads one section mesh may get from splitting (keeps clear of the 65536 per buffer Voxy has room for). */
    private static final int MAX_EXTRA_QUADS = 24_000;
    /** Blocks around a source that are looked at, at most (the distance tables reach 16 blocks, plus one of slack). */
    public static final int MARGIN = 17;
    /** Size, in blocks, of one cell of the "light reaches here" mask. */
    private static final int MASK_CELL_SHIFT = 2;
    private static final int MASK_PAD = (MARGIN + 3) >> MASK_CELL_SHIFT; // cells of mask around the section
    private static final int MASK_PAD_BLOCKS = MASK_PAD << MASK_CELL_SHIFT;
    /** Marking more mask cells than this for one section means "light is everywhere": split all. */
    private static final long MARK_BUDGET = 4_000_000L;

    private static final class State {
        boolean active;
        boolean all;
        int lvl;
        int ox, oy, oz; // block position of the section's first cell
        int side;       // mask cells per axis
        boolean[] mask = new boolean[0];
        int extra;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private LodSplit() {
    }

    /** How far, in blocks, a source of this colour carries light (0..MARGIN). Same rule the shader's fade uses. */
    public static int extentOf(int rgb) {
        int top = Math.max(rgb & 255, Math.max((rgb >> 8) & 255, (rgb >> 16) & 255));
        if (top <= 0)
            return 0;
        int range = Math.max(1, Math.min(16, ColorLightEngineHolder.getMaxRangeBlocks()));
        return Math.min(MARGIN, (int) Math.ceil(top / 255.0 * range) + 1);
    }

    public static boolean enabled() {
        return ColorLightClient.config != null && ColorLightClient.config.VOXY_LIGHT && ColorLightClient.config.ENABLE;
    }

    /** A section is about to be meshed (level, position in sections of that level). */
    public static void begin(int lvl, int sx, int sy, int sz) {
        State s = STATE.get();
        s.active = false;
        s.all = false;
        s.extra = 0;
        if (lvl > MAX_LOD || !enabled())
            return;
        LodLightStore store = LodLightHub.store();
        if (store == null)
            return;

        int span = 32 << lvl;
        int ox = sx * span, oy = sy * span, oz = sz * span;
        int side = (span >> MASK_CELL_SHIFT) + 2 * MASK_PAD;
        int total = side * side * side;

        boolean any = false;
        long marks = 0;
        boolean[] mask = s.mask;
        boolean cleared = false;

        int cx0 = Math.floorDiv(ox - MARGIN, 32), cx1 = Math.floorDiv(ox + span + MARGIN, 32);
        int cz0 = Math.floorDiv(oz - MARGIN, 32), cz1 = Math.floorDiv(oz + span + MARGIN, 32);
        ConcurrentHashMap<Long, int[]> columns = store.columns;
        outer:
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int[] src = columns.get(LodLightStore.colKey(cx, cz));
                if (src == null)
                    continue;
                for (int i = 0; i + 3 < src.length; i += 4) {
                    int ext = extentOf(src[i + 3]);
                    if (ext <= 0)
                        continue;
                    int x = src[i], y = src[i + 1], z = src[i + 2];
                    // does this source's box touch the section at all?
                    if (x + ext < ox || x - ext >= ox + span || y + ext < oy || y - ext >= oy + span || z + ext < oz || z - ext >= oz + span)
                        continue;
                    if (!any) {
                        if (mask.length < total)
                            mask = s.mask = new boolean[total];
                        else
                            Arrays.fill(mask, 0, total, false);
                        any = true;
                        cleared = true;
                    }
                    int x0 = clampCell(((x - ext) - ox + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
                    int x1 = clampCell(((x + ext) - ox + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
                    int y0 = clampCell(((y - ext) - oy + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
                    int y1 = clampCell(((y + ext) - oy + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
                    int z0 = clampCell(((z - ext) - oz + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
                    int z1 = clampCell(((z + ext) - oz + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
                    for (int a = x0; a <= x1; a++)
                        for (int b = y0; b <= y1; b++) {
                            int row = (a * side + b) * side;
                            for (int c = z0; c <= z1; c++)
                                mask[row + c] = true;
                        }
                    marks += (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
                    if (marks > MARK_BUDGET) {
                        s.all = true;
                        break outer;
                    }
                }
            }
        }
        if (!any)
            return;
        s.active = cleared;
        s.lvl = lvl;
        s.ox = ox;
        s.oy = oy;
        s.oz = oz;
        s.side = side;
    }

    private static int clampCell(int v, int side) {
        return v < 0 ? 0 : Math.min(v, side - 1);
    }

    /** The section has been meshed (or meshing failed): forget it. */
    public static void end() {
        STATE.get().active = false;
    }

    /**
     * Should the quad about to be emitted be split into single cells?
     *
     * @param axis 0 = Y (a face seen from above or below), 1 = Z, 2 = X, as in Voxy's mesher
     * @param aux  the mesher's layer along that axis
     * @param a0   first cell of the quad along its first plane axis, a1 the last (inclusive)
     * @param b0   same for the second plane axis, b1 the last
     *             (the plane axes are X,Z for axis 0; X,Y for axis 1; Z,Y for axis 2)
     */
    public static boolean wants(int axis, int aux, int a0, int a1, int b0, int b1) {
        State s = STATE.get();
        if (!s.active)
            return false;
        int cells = (a1 - a0 + 1) * (b1 - b0 + 1);
        if (cells <= 1 || s.extra + cells > MAX_EXTRA_QUADS)
            return false;

        boolean hit;
        if (s.all) {
            hit = true;
        } else {
            int minX, maxX, minY, maxY, minZ, maxZ;
            int lo = aux - 1, hi = aux + 1;
            switch (axis) {
                case 0 -> { minX = a0; maxX = a1; minZ = b0; maxZ = b1; minY = lo; maxY = hi; }
                case 1 -> { minX = a0; maxX = a1; minY = b0; maxY = b1; minZ = lo; maxZ = hi; }
                default -> { minZ = a0; maxZ = a1; minY = b0; maxY = b1; minX = lo; maxX = hi; }
            }
            int scale = 1 << s.lvl;
            hit = touches(s,
                    s.ox + minX * scale, s.ox + (maxX + 1) * scale - 1,
                    s.oy + minY * scale, s.oy + (maxY + 1) * scale - 1,
                    s.oz + minZ * scale, s.oz + (maxZ + 1) * scale - 1);
        }
        if (hit)
            s.extra += cells;
        return hit;
    }

    private static boolean touches(State s, int bx0, int bx1, int by0, int by1, int bz0, int bz1) {
        int side = s.side;
        int x0 = clampCell((bx0 - s.ox + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
        int x1 = clampCell((bx1 - s.ox + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
        int y0 = clampCell((by0 - s.oy + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
        int y1 = clampCell((by1 - s.oy + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
        int z0 = clampCell((bz0 - s.oz + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
        int z1 = clampCell((bz1 - s.oz + MASK_PAD_BLOCKS) >> MASK_CELL_SHIFT, side);
        boolean[] mask = s.mask;
        for (int a = x0; a <= x1; a++)
            for (int b = y0; b <= y1; b++) {
                int row = (a * side + b) * side;
                for (int c = z0; c <= z1; c++)
                    if (mask[row + c])
                        return true;
            }
        return false;
    }
}
