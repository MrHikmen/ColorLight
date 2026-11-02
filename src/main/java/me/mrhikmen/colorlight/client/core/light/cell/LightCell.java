package me.mrhikmen.colorlight.client.core.light.cell;

/**
 * What one cell of the light field holds: up to TWO lights, each a brightness (1..255) and the id of its colour in the
 * {@link LightPalette}, plus a few flags of the engine. Nothing here allocates; a cell is a plain {@code long}:
 * <pre>
 * bits  0..7   brightness of slot A        bits 16..23  brightness of slot B
 * bits  8..15  palette id of slot A        bits 24..31  palette id of slot B
 * bits 32..39  engine flags (kept as they are)
 * </pre>
 * A slot is empty when its brightness is 0. The slots are not ordered.
 * <p>
 * <b>Why two slots and not RGB.</b> The colour of a cell is what the lights in it add up to (the per-channel maximum
 * of {@code brightness * hue}), and each light fades with its own distance. With the brightness of each light known,
 * two colours blend smoothly in the overlap - a faint tail of one light cannot recolour the strong light of another.
 * A third colour in the same cell is kept only if it is stronger than the weaker of the two already there.
 * <p>
 * <b>Order independence.</b> {@link #insert} gives the same cell for any order of insertions: it ends up holding the
 * two strongest colours (ties go to the lower id), each at its highest brightness. That is what lets floods be
 * split between threads and merged by simply inserting.
 * <p>
 * <b>For the GPU</b> a cell is packed into one 32-bit word ({@link #pack}), see there.
 */
public final class LightCell {

    /** The bits of the two slots. */
    public static final long SLOTS = 0xFFFFFFFFL;
    /** The bits of the engine's flags. */
    public static final long FLAGS = 0xFFL << 32;

    /**
     * Set by {@link #insert} when it had to leave a light out because both slots hold stronger lights of other colours
     * (a third colour in the cell). The engine uses it to know that a cell may hide light that has to be flooded
     * again when the light in front of it is removed. It is set exactly when three or more different colours have
     * been inserted, whatever the order.
     */
    public static final long OVERFLOW = 1L << 36;

    /** Bit of the packed GPU word that marks a cell holding a light-blocking block (set by the volume, not here). */
    public static final int PACKED_OPAQUE_BIT = 1 << 30;

    private LightCell() {
    }

    public static boolean isLit(long cell) {
        return (cell & SLOTS) != 0;
    }

    public static int levelA(long cell) {
        return (int) (cell & 0xFF);
    }

    public static int idA(long cell) {
        return (int) ((cell >> 8) & 0xFF);
    }

    public static int levelB(long cell) {
        return (int) ((cell >> 16) & 0xFF);
    }

    public static int idB(long cell) {
        return (int) ((cell >> 24) & 0xFF);
    }

    /** Brightness this cell holds of the colour with this palette id (0 if none). */
    public static int levelOf(long cell, int id) {
        if (id == 0)
            return 0;
        int a = levelA(cell);
        if (a != 0 && idA(cell) == id)
            return a;
        int b = levelB(cell);
        if (b != 0 && idB(cell) == id)
            return b;
        return 0;
    }

    /** The cell with the light of this colour set to {@code level} (0 removes it); other slot and flags unchanged. */
    public static long withLevel(long cell, int id, int level) {
        if (levelA(cell) != 0 && idA(cell) == id)
            return setA(cell, level == 0 ? 0 : id, level);
        if (levelB(cell) != 0 && idB(cell) == id)
            return setB(cell, level == 0 ? 0 : id, level);
        return level == 0 ? cell : insert(cell, id, level);
    }

    /** Both slots' lights of one cell, calling nothing but handy for loops: {@code (id << 8) | level} or 0 for an empty slot. */
    public static int slotA(long cell) {
        int l = levelA(cell);
        return l == 0 ? 0 : (idA(cell) << 8) | l;
    }

    public static int slotB(long cell) {
        int l = levelB(cell);
        return l == 0 ? 0 : (idB(cell) << 8) | l;
    }

    private static long setA(long cell, int id, int level) {
        return (cell & ~0xFFFFL) | (level & 0xFF) | ((long) (level == 0 ? 0 : id) << 8);
    }

    private static long setB(long cell, int id, int level) {
        return (cell & ~0xFFFF0000L) | ((long) (level & 0xFF) << 16) | ((long) (level == 0 ? 0 : id) << 24);
    }

    /**
     * Adds a light of colour {@code id} and brightness {@code level} to the cell: raises the colour's own slot if it
     * has one, else takes a free slot, else replaces the weaker slot when this light is stronger (equal strength: the
     * lower id wins). Returns the cell unchanged when nothing needs to change.
     */
    public static long insert(long cell, int id, int level) {
        if (level <= 0 || id <= 0)
            return cell;
        if (level > 255)
            level = 255;

        int la = levelA(cell), lb = levelB(cell);
        if (la != 0 && idA(cell) == id)
            return level > la ? setA(cell, id, level) : cell;
        if (lb != 0 && idB(cell) == id)
            return level > lb ? setB(cell, id, level) : cell;

        if (la == 0)
            return setA(cell, id, level);
        if (lb == 0)
            return setB(cell, id, level);

        // both slots hold other colours: the weaker one makes room if this light is stronger
        int ia = idA(cell), ib = idB(cell);
        boolean aWeaker = la < lb || (la == lb && ia > ib);
        int weakerLevel = aWeaker ? la : lb;
        int weakerId = aWeaker ? ia : ib;
        if (level > weakerLevel || (level == weakerLevel && id < weakerId))
            return (aWeaker ? setA(cell, id, level) : setB(cell, id, level)) | OVERFLOW; // the weaker light is left out
        return cell | OVERFLOW; // this light is left out
    }

    /** {@code into} with every light of {@code other} inserted; flags of {@code into} kept. */
    public static long merge(long into, long other) {
        long result = into | (other & OVERFLOW);
        int a = levelA(other);
        if (a != 0)
            result = insert(result, idA(other), a);
        int b = levelB(other);
        if (b != 0)
            result = insert(result, idB(other), b);
        return result;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Colour
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The colour the cell shows: per channel the maximum over its lights of {@code hue * brightness / 255}
     * ({@code 0x00BBGGRR}). The GPU shader does the same sum (see {@code colorlight_data.glsl}).
     */
    public static int toRgb(long cell, LightPalette palette) {
        return maxRgb(palette.rgbOf(idA(cell)), levelA(cell), palette.rgbOf(idB(cell)), levelB(cell));
    }

    private static int maxRgb(int hueA, int la, int hueB, int lb) {
        int r = Math.max(scale(hueA & 0xFF, la), scale(hueB & 0xFF, lb));
        int g = Math.max(scale((hueA >> 8) & 0xFF, la), scale((hueB >> 8) & 0xFF, lb));
        int b = Math.max(scale((hueA >> 16) & 0xFF, la), scale((hueB >> 16) & 0xFF, lb));
        return r | (g << 8) | (b << 16);
    }

    private static int scale(int hueChannel, int level) {
        return (hueChannel * level + 127) / 255;
    }

    // ------------------------------------------------------------------------------------------------------------
    // GPU word
    // ------------------------------------------------------------------------------------------------------------

    /** The ratio of the weaker light to the stronger one is stored in 5 bits as {@code ratio * RATIO_STEPS}. */
    private static final int RATIO_STEPS = 31;
    /** Set in the GPU word of a cell that holds two lights (see {@link #pack}). */
    public static final int PACKED_TWO_LIGHTS_BIT = 1 << 29;

    /**
     * Packs the lights of a cell into the word the GPU reads. Bit 31 stays 0 and bit 30 is {@link #PACKED_OPAQUE_BIT}.
     * <pre>
     * one light  (bit 29 = 0):  bits 0..23  the colour it shows, 0x00BBGGRR (hue scaled by brightness)
     * two lights (bit 29 = 1):  bits  0..7   I   brightness of the stronger light
     *                           bits  8..15  id1 palette id of the stronger light
     *                           bits 16..23  id2 palette id of the other light
     *                           bits 24..28  r   brightness of the other light relative to the stronger, r * 31 (1..31)
     * </pre>
     * A cell with one light - nearly all of them - needs no palette lookup in the shader, which keeps the per-pixel work
     * what it was with plain colours; only where two lights meet the shader reads the palette. The other light's
     * brightness is {@code I * r / 31}, known to about 4 of 255.
     */
    public static int pack(long cell, LightPalette palette) {
        int la = levelA(cell), lb = levelB(cell);
        int ia = idA(cell), ib = idB(cell);
        if (la == 0 && lb == 0)
            return 0;
        if (la == 0 || (lb != 0 && (lb > la || (lb == la && ib < ia)))) {
            int tl = la, ti = ia;
            la = lb;
            ia = ib;
            lb = tl;
            ib = ti;
        }
        int ratio = lb == 0 ? 0 : (lb * RATIO_STEPS + (la >> 1)) / la;
        if (ratio == 0) // one light (or the other is too faint next to it to be seen)
            return maxRgb(palette.rgbOf(ia), la, 0, 0);
        return la | (ia << 8) | (ib << 16) | (ratio << 24) | PACKED_TWO_LIGHTS_BIT;
    }

    /** The colour a packed word shows; the same arithmetic as the shader's {@code cl_decodeCell}. */
    public static int packedToRgb(int word, LightPalette palette) {
        if ((word & PACKED_TWO_LIGHTS_BIT) == 0)
            return word & 0xFFFFFF;
        int level = word & 0xFF;
        int hueA = palette.rgbOf((word >> 8) & 0xFF);
        int ratio = (word >> 24) & 0x1F;
        int second = (level * ratio + RATIO_STEPS / 2) / RATIO_STEPS;
        return maxRgb(hueA, level, palette.rgbOf((word >> 16) & 0xFF), second);
    }
}
