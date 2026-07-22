package me.mrhikmen.colorlight.core.scanner.texture;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

import static me.mrhikmen.colorlight.core.scanner.texture.ScanTextureBlock.*;

public class SearchBestPixel {
    public static PixelData search(ResourceLocation textureId){
        ResourceLocation fileId = ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(), "textures/" + textureId.getPath() + ".png");
        List<PixelData> pixels = ScanTextureBlock.scan(fileId);


        if (pixels == null || pixels.isEmpty())
            return null;

        long avgR = 0;
        long avgG = 0;
        long avgB = 0;

        int count = 0;

        for (PixelData p : pixels) {
            if (p.a == 0) continue;
            avgR += p.r;
            avgG += p.g;
            avgB += p.b;
            count++;
        }

        if (count == 0)
            return null; // все пиксели прозрачные, валидного цвета нет

        avgR /= count;
        avgG /= count;
        avgB /= count;

        for (PixelData pixel : pixels) {
            if (pixel.a == 0) continue;
            pixel.score = brightness(pixel) * 0.35 + saturation(pixel) * 0.35 + anomaly(pixel, (int) avgR, (int) avgG, (int) avgB) * 0.15;
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