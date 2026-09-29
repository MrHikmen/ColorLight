package me.mrhikmen.colorlight.client.core.scanner.texture;

import me.mrhikmen.colorlight.client.ColorLightClient;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Texture access and the individual pixel-scoring terms used by {@link SearchBestPixel}.
 * <p>
 * Pixels are held as plain int arrays (one int per pixel) instead of one {@code PixelData} object each
 * plus a 2D object array plus a list: a 512x512 resource-pack texture used to allocate ~262k objects
 * just to be scanned once. The formulas are unchanged.
 */
public class ScanTextureBlock {

    public static class TextureData {

        public final int width;
        public final int height;

        /** Row-major (index = y * width + x), each value exactly what {@code NativeImage#getPixel} returned. */
        public final int[] rgba;

        public TextureData(int width, int height, int[] rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }

    public static TextureData scan(Identifier texture) {

        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);

        if (resource.isEmpty()) {
            ColorLightClient.LOGGER.warn("[ColorLight] Missing texture {}, block skipped.", texture);
            return null;
        }

        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {

            int width = image.getWidth();
            int height = image.getHeight();

            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[row + x] = image.getPixel(x, y);
                }
            }
            return new TextureData(width, height, pixels);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Channel decoding is deliberately identical to the old PixelData construction:
    // PixelData.r = bits 16..23, PixelData.g = bits 8..15, PixelData.b = bits 0..7.

    public static int alpha(int rgba) {
        return (rgba >>> 24) & 255;
    }

    public static int red(int rgba) {
        return (rgba >>> 16) & 255;
    }

    public static int green(int rgba) {
        return (rgba >>> 8) & 255;
    }

    public static int blue(int rgba) {
        return rgba & 255;
    }

    public static boolean isVisible(int rgba) {
        return alpha(rgba) > 0;
    }

    public static double brightness(int rgba) {
        return (0.2126 * red(rgba) + 0.7152 * green(rgba) + 0.0722 * blue(rgba)) / 255.0;
    }

    /** Mean brightness of the visible pixels in the 3x3 patch around (x, y), including the centre. */
    public static double localBrightness(TextureData tex, int x, int y) {

        double sum = 0;
        int count = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {

                int nx = x + dx;
                int ny = y + dy;

                if (nx < 0 || ny < 0)
                    continue;

                if (nx >= tex.width || ny >= tex.height)
                    continue;

                int p = tex.rgba[ny * tex.width + nx];

                if (!isVisible(p))
                    continue;

                sum += brightness(p);
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private static final int COLOR_DISTANCE = 70;
    private static final int COLOR_DISTANCE_SQ = COLOR_DISTANCE * COLOR_DISTANCE;

    /**
     * Sizes of the colour regions of the texture, indexed like {@link TextureData#rgba}.
     * A region grows (8-neighbourhood) from its first pixel over visible, not-yet-claimed pixels that lie within
     * {@value #COLOR_DISTANCE} colour distance of that <i>first</i> pixel - the same rule as before.
     * Expensive, so callers only ask for it when region size actually influences the score.
     */
    public static int[] regionSizes(TextureData tex) {

        int w = tex.width;
        int h = tex.height;
        int total = w * h;

        int[] regionSize = new int[total];
        boolean[] visited = new boolean[total];
        int[] queue = new int[total];

        for (int start = 0; start < total; start++) {

            int startPixel = tex.rgba[start];
            if (!isVisible(startPixel) || visited[start])
                continue;

            int sr = red(startPixel), sg = green(startPixel), sb = blue(startPixel);

            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;

            while (head < tail) {
                int cur = queue[head++];
                int cx = cur % w;
                int cy = cur / w;

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0)
                            continue;

                        int nx = cx + dx;
                        int ny = cy + dy;

                        if (nx < 0 || ny < 0 || nx >= w || ny >= h)
                            continue;

                        int ni = ny * w + nx;
                        if (visited[ni])
                            continue;

                        int n = tex.rgba[ni];
                        if (!isVisible(n))
                            continue;

                        int dr = sr - red(n);
                        int dg = sg - green(n);
                        int db = sb - blue(n);
                        if (dr * dr + dg * dg + db * db > COLOR_DISTANCE_SQ)
                            continue;

                        visited[ni] = true;
                        queue[tail++] = ni;
                    }
                }
            }

            for (int i = 0; i < tail; i++) {
                regionSize[queue[i]] = tail;
            }
        }
        return regionSize;
    }

    public static double anomaly(int rgba, int avgR, int avgG, int avgB) {

        double dr = red(rgba) - avgR;
        double dg = green(rgba) - avgG;
        double db = blue(rgba) - avgB;

        double distance = Math.sqrt(dr * dr + dg * dg + db * db);

        return distance / 441.67;
    }

    public static double saturation(int rgba) {

        int max = Math.max(red(rgba), Math.max(green(rgba), blue(rgba)));
        int min = Math.min(red(rgba), Math.min(green(rgba), blue(rgba)));

        return (max - min) / 255.0;
    }

    public static double whitePenalty(int rgba) {

        double saturation = saturation(rgba);

        if (saturation < 0.08 && brightness(rgba) > 0.85)
            return 0.3;

        return 1.0;
    }

    public static double glowColorScore(int rgba) {

        double saturation = saturation(rgba);

        double bright = brightness(rgba);

        return bright * 0.6 + saturation * 0.4;
    }
}
