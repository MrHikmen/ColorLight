package me.mrhikmen.colorlight.light;

public final class ColorLightUtil {

    public static final int MAX = 255;
    public static final int EMPTY = 0;

    public static int pack(int r, int g, int b) {
        return (clamp(r)) | (clamp(g) << 8) | (clamp(b) << 16);
    }

    public static int r(int packed) {
        return packed & 0xFF;
    }

    public static int g(int packed) {
        return (packed >> 8) & 0xFF;
    }

    public static int b(int packed) {
        return (packed >> 16) & 0xFF;
    }

    public static int clamp(int value) {
        return Math.max(0, Math.min(MAX, value));
    }

    public static boolean isEmpty(int packed) {
        return packed == EMPTY;
    }

    public static int max(int a, int b) {
        int r = Math.max(r(a), r(b));
        int g = Math.max(g(a), g(b));
        int bl = Math.max(b(a), b(b));
        return pack(r, g, bl);
    }

    private static final float INTENSITY_SCALE = 0.85f;

    private static final float DAYLIGHT_SUPPRESSION_STRENGTH = 1.0f;

    public static int toArgb(int packed) {
        int r = r(packed);
        int g = g(packed);
        int b = b(packed);

        int maxLevel = Math.max(r, Math.max(g, b));
        if (maxLevel == 0) {
            return 0xFFFFFFFF; // белый — эффекта нет вообще
        }

        float strength = (maxLevel / (float) MAX) * INTENSITY_SCALE;

        float hr = r / (float) maxLevel;
        float hg = g / (float) maxLevel;
        float hb = b / (float) maxLevel;

        int ir = blendChannel(hr, strength);
        int ig = blendChannel(hg, strength);
        int ib = blendChannel(hb, strength);

        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }

    public static int toArgb(int packed, float daylightFactor) {
        int base = toArgb(packed);
        if (base == 0xFFFFFFFF)
            return base;

        float k = clamp01(daylightFactor) * DAYLIGHT_SUPPRESSION_STRENGTH;

        int r = (base >> 16) & 0xFF;
        int g = (base >> 8) & 0xFF;
        int b = base & 0xFF;

        int fr = Math.round(255 + (r - 255) * (1f - k));
        int fg = Math.round(255 + (g - 255) * (1f - k));
        int fb = Math.round(255 + (b - 255) * (1f - k));

        return 0xFF000000 | (fr << 16) | (fg << 8) | fb;
    }

    public static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int blendChannel(float hueChannel, float strength) {
        float value = 1f + (hueChannel - 1f) * strength;
        value = Math.max(0f, Math.min(1f, value));
        return Math.round(value * 255f);
    }

    private ColorLightUtil() {
    }
}