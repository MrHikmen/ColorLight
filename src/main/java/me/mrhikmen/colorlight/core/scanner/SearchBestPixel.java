package me.mrhikmen.colorlight.core.scanner;

import me.mrhikmen.colorlight.ColorLightClient;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

import static me.mrhikmen.colorlight.core.scanner.ScanTextureBlock.*;

public class SearchBestPixel {
    public static PixelData search(ResourceLocation textureId){
        ResourceLocation fileId = ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(), "textures/" + textureId.getPath() + ".png");
        List<PixelData> pixels = ScanTextureBlock.scan(fileId);

        int avgR = 0;
        int avgG = 0;
        int avgB = 0;

        int count = 0;

        for (PixelData p : pixels) {
            if (p.a == 0) continue;
            avgR += p.r;
            avgG += p.g;
            avgB += p.b;
            count++;
        }

        avgR /= count;
        avgG /= count;
        avgB /= count;

        for (PixelData pixel : pixels) {
            if (pixel.a == 0) continue;
            pixel.score = brightness(pixel) * 0.35 + saturation(pixel) * 0.35 + anomaly(pixel, avgR, avgG, avgB) * 0.30;
        }

        PixelData best = null;

        for (PixelData pixel : pixels) {
            if (pixel.a == 0) continue;
            if (best == null || pixel.score > best.score) {
                best = pixel;
            }
        }
        return best;
    }
}
