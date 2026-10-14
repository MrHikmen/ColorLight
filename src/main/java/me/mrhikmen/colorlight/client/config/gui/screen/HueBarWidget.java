package me.mrhikmen.colorlight.client.config.gui.screen;

import me.mrhikmen.colorlight.client.config.Translatable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;

public class HueBarWidget extends ColorPickerWidget {

    private static final int[] STOPS = {
            0xFFFF0000,
            0xFFFFFF00,
            0xFF00FF00,
            0xFF00FFFF,
            0xFF0000FF,
            0xFFFF00FF,
            0xFFFF0000
    };

    public HueBarWidget(int x, int y, int width, int height, ColorPickerMath color, Runnable onChange) {
        super(x, y, width, height, Translatable.SHADE, color, onChange);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int x0 = this.getX();
        int y0 = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();

        int segments = STOPS.length - 1;
        int segHeight = h / segments;

        for (int i = 0; i < segments; i++) {
            int segY0 = y0 + i * segHeight;
            int segY1 = (i == segments - 1) ? (y0 + h) : (segY0 + segHeight);
            graphics.fillGradient(x0, segY0, x0 + w, segY1, STOPS[i], STOPS[i + 1]);
        }

        int markerY = y0 + Math.round(this.color.hue * h);
        graphics.fillGradient(x0, markerY - 1, x0 + w, markerY + 1, 0xFFFFFFFF, 0xFFFFFFFF);
    }

    @Override
    protected void applyFromMouse(MouseButtonEvent event) {
        float relY = (float) ((event.y() - this.getY()) / (double) this.getHeight());
        this.color.onHueBarClick(Mth.clamp(relY, 0f, 1f));
        this.onChange.run();
    }
}
