package me.mrhikmen.colorlight.client.core.light.cell;

import java.util.Arrays;

/**
 * The colours (hues) lights in one world use, numbered 1..255. A stored light is "brightness + palette id" instead of
 * three colour channels, so the palette is what turns an id back into a colour.
 * <p>
 * A hue is a colour whose brightest channel is 255 (the brightness is kept separately, see {@link LightCell}).
 * Hues that differ by no more than {@link #TOLERANCE} in every channel share an id (the lowest one that is close
 * enough, so the id of a colour never changes), so the dozens of near-identical
 * colours a texture scan produces do not use up the 255 ids. When all ids are taken a new hue gets the id of the
 * closest existing one.
 * <p>
 * The palette only ever grows while a world is loaded: an id keeps its colour, so already stored cells never need
 * rewriting. {@link #idFor} may be called from several threads (the dynamic light workers), {@link #rgbOf} is lock-free.
 */
public final class LightPalette {

    public static final int MAX_ID = 255;
    /** Largest per-channel difference (0..255) at which two hues count as the same colour. */
    public static final int TOLERANCE = 6;

    /** Index = id; entry 0 is unused. Replaced (never modified) when a hue is added, so readers need no lock. */
    private volatile int[] hues = new int[]{0};
    private volatile int version;

    /** The brightest channel of a packed {@code 0x00BBGGRR} colour: how bright the light it describes is (0..255). */
    public static int peakOf(int rgb) {
        return Math.max(rgb & 0xFF, Math.max((rgb >> 8) & 0xFF, (rgb >> 16) & 0xFF));
    }

    /** The colour scaled so its brightest channel is 255; 0 for black. */
    public static int hueOf(int rgb) {
        int r = rgb & 0xFF, g = (rgb >> 8) & 0xFF, b = (rgb >> 16) & 0xFF;
        int peak = Math.max(r, Math.max(g, b));
        if (peak <= 0)
            return 0;
        int half = peak >> 1;
        return Math.min(255, (r * 255 + half) / peak)
                | (Math.min(255, (g * 255 + half) / peak) << 8)
                | (Math.min(255, (b * 255 + half) / peak) << 16);
    }

    /** Id (1..255) of the hue of this colour, adding it when there is no close enough one; 0 only for black. */
    public int idFor(int rgb) {
        int hue = hueOf(rgb);
        if (hue == 0)
            return 0;

        int match = firstWithin(hues, hue);
        if (match > 0)
            return match;

        synchronized (this) {
            int[] current = hues;
            match = firstWithin(current, hue); // another thread may have added it meanwhile
            if (match > 0)
                return match;
            if (current.length > MAX_ID)
                return closest(current, hue); // full: the closest one

            int[] next = Arrays.copyOf(current, current.length + 1);
            next[current.length] = hue;
            hues = next;
            version++;
            return current.length;
        }
    }

    /** The hue of an id (brightest channel 255), 0 for an unknown id. Lock-free. */
    public int rgbOf(int id) {
        int[] h = hues;
        return id > 0 && id < h.length ? h[id] : 0;
    }

    /** Number of ids in use. */
    public int size() {
        return hues.length - 1;
    }

    /** Changes whenever an id is added. */
    public int version() {
        return version;
    }

    /** Copies the hues into {@code out[offset + id]} for every id 1..{@link #MAX_ID}; unused ids become 0. */
    public void copyTo(int[] out, int offset) {
        int[] h = hues;
        for (int id = 1; id <= MAX_ID; id++)
            out[offset + id] = id < h.length ? h[id] : 0;
    }

    /**
     * The LOWEST id within {@link #TOLERANCE} of the hue, or 0. Ids are only ever appended, so the answer for a given
     * hue never changes once there is one - a stored light keeps meaning the same colour (a source can be looked up again
     * by its own colour and find the id it was stored with).
     */
    private static int firstWithin(int[] h, int hue) {
        for (int id = 1; id < h.length; id++) {
            if (distance(h[id], hue) <= TOLERANCE)
                return id;
        }
        return 0;
    }

    private static int closest(int[] h, int hue) {
        int best = 0, bestDistance = Integer.MAX_VALUE;
        for (int id = 1; id < h.length; id++) {
            int d = distance(h[id], hue);
            if (d < bestDistance) {
                bestDistance = d;
                best = id;
            }
        }
        return best;
    }

    private static int distance(int a, int b) {
        return Math.max(Math.abs((a & 0xFF) - (b & 0xFF)),
                Math.max(Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)), Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))));
    }
}
