package me.mrhikmen.colorlight.core.scanner.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ScanTextureBlock {

    public static List<PixelData> scan(ResourceLocation texture) {

        List<PixelData> pixels = new ArrayList<>();
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(texture).orElseThrow();

        try (InputStream stream = resource.open()) {

            NativeImage image = NativeImage.read(stream);

            for (int y = 0; y < image.getHeight(); y++) {

                for (int x = 0; x < image.getWidth(); x++) {

                    int rgba = image.getPixelRGBA(x, y);

                    int a = (rgba >>> 24) & 255;
                    int b = (rgba >>> 16) & 255;
                    int g = (rgba >>> 8) & 255;
                    int r = rgba & 255;

                    pixels.add(new PixelData(x, y, r, g, b, a));

                }
            }
            image.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return pixels;
    }
    public static double brightness(PixelData p) {

        return Math.max(p.r, Math.max(p.g, p.b)) / 255.0;
    }
    public static double saturation(PixelData p) {

        int max = Math.max(p.r, Math.max(p.g, p.b));
        int min = Math.min(p.r, Math.min(p.g, p.b));

        return (max - min) / 255.0;
    }
    public static double anomaly(PixelData p, int avgR, int avgG, int avgB) {

        double dr = p.r - avgR;
        double dg = p.g - avgG;
        double db = p.b - avgB;

        return Math.sqrt(dr * dr + dg * dg + db * db) / 441.67;
    }
}