package me.mrhikmen.colorlight.core.scanner;

public class PixelData {

    public final int x;
    public final int y;

    public final int r;
    public final int g;
    public final int b;
    public final int a;

    public double score;
    public boolean isVisible() {
        return a > 0;
    }

    public PixelData(int x, int y, int r, int g, int b, int a) {
        this.x = x;
        this.y = y;

        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }
}