package me.mrhikmen.colorlight.core.scanner.texture;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.ColorLightConfig;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

public class SearchBestPixel {

    /**
     * Result per texture for the current resource reload. Many blocks share textures (torch / wall torch,
     * every state variant of a block, ...), and a texture's best pixel only depends on the pixels and on the
     * scoring weights, so each texture is scanned once per reload rather than once per block that uses it.
     * A cached "no result" is stored too.
     */
    private static final Map<Identifier, PixelData> CACHE = new HashMap<>();

    public static void clearCache() {
        CACHE.clear();
    }

    public static PixelData search(Identifier textureId) {
        if (CACHE.containsKey(textureId))
            return CACHE.get(textureId);

        PixelData result = compute(textureId);
        CACHE.put(textureId, result);
        return result;
    }

    private static PixelData compute(Identifier textureId) {

        Identifier fileId = Identifier.fromNamespaceAndPath(textureId.getNamespace(), "textures/" + textureId.getPath() + ".png");

        ScanTextureBlock.TextureData texture = ScanTextureBlock.scan(fileId);

        if (texture == null || texture.rgba.length == 0)
            return null;

        int w = texture.width;
        int h = texture.height;
        int[] rgba = texture.rgba;

        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        int count = 0;

        for (int p : rgba) {
            if (!ScanTextureBlock.isVisible(p))
                continue;

            sumR += ScanTextureBlock.red(p);
            sumG += ScanTextureBlock.green(p);
            sumB += ScanTextureBlock.blue(p);

            count++;
        }

        if (count == 0)
            return null;

        int avgR = (int) (sumR / count);
        int avgG = (int) (sumG / count);
        int avgB = (int) (sumB / count);

        // The weights are ints and "/ 100" is integer division, exactly as before: only whole multiples of 100 count.
        ColorLightConfig cfg = ColorLightClient.config;
        int wBrightness = cfg.BRIGHTNESS_WEIGHT / 100;
        int wLocal = cfg.LOCAL_WEIGHT / 100;
        int wRegion = cfg.REGION_WEIGHT / 100;
        int wAlpha = cfg.ALPHA_WEIGHT / 100;
        int wAnomaly = cfg.ANOMALY_WEIGHT / 100;
        int wSaturation = cfg.SATURATION_WEIGHT / 100;
        int wGlow = cfg.GLOWCOLORSCORE_WEIGHT / 100;
        int wWhite = cfg.WHITEPENALTY_WEIGHT / 100;

        // Region analysis is the costly part and contributes nothing while its weight is 0 (the default).
        int[] regionSize = null;
        int maxRegion = 1;
        if (wRegion != 0) {
            regionSize = ScanTextureBlock.regionSizes(texture);
            for (int size : regionSize) {
                maxRegion = Math.max(maxRegion, size);
            }
        }

        int bestIndex = -1;
        double bestScore = 0;

        for (int index = 0; index < rgba.length; index++) {

            int pixel = rgba[index];
            if (!ScanTextureBlock.isVisible(pixel))
                continue;

            int x = index % w;
            int y = index / w;

            double brightness = ScanTextureBlock.brightness(pixel);

            double localBrightness = ScanTextureBlock.localBrightness(texture, x, y);

            double regionScore = regionSize == null ? 0 : regionSize[index] / (double) maxRegion;

            double anomaly = ScanTextureBlock.anomaly(pixel, avgR, avgG, avgB);

            double alphaScore = ScanTextureBlock.alpha(pixel) / 255.0;

            double saturation = ScanTextureBlock.saturation(pixel);

            double whitePenalty = ScanTextureBlock.whitePenalty(pixel);

            double glowColorScore = ScanTextureBlock.glowColorScore(pixel);

            double score =
                    brightness * wBrightness +
                    localBrightness * wLocal +
                    regionScore * wRegion +
                    alphaScore * wAlpha +
                    anomaly * wAnomaly +
                    saturation * wSaturation +
                    glowColorScore * wGlow +
                    whitePenalty * wWhite;

            if (bestIndex < 0 || score > bestScore) {
                bestIndex = index;
                bestScore = score;
            }
        }

        if (bestIndex < 0)
            return null;

        int best = rgba[bestIndex];
        PixelData result = new PixelData(bestIndex % w, bestIndex / w,
                ScanTextureBlock.red(best), ScanTextureBlock.green(best), ScanTextureBlock.blue(best),
                ScanTextureBlock.alpha(best));
        result.score = bestScore;
        return result;
    }
}
