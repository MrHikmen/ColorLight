package me.mrhikmen.colorlight.client.config.gui.screen;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Shared behaviour for the two color-picker widgets ({@link HueBarWidget} and {@link ColorSquareWidget}): both
 * just forward clicks and drags to a mouse handler and narrate their own title. Only how each one paints itself,
 * and how it turns a mouse position into a new value, differs.
 */
abstract class ColorPickerWidget extends AbstractWidget {

    protected final ColorPickerMath color;
    protected final Runnable onChange;

    protected ColorPickerWidget(int x, int y, int width, int height, Component message, ColorPickerMath color, Runnable onChange) {
        super(x, y, width, height, message);
        this.color = color;
        this.onChange = onChange;
    }

    @Override
    public final void onClick(MouseButtonEvent event, boolean doubleClick) {
        applyFromMouse(event);
    }

    @Override
    protected final void onDrag(MouseButtonEvent event, double dx, double dy) {
        applyFromMouse(event);
    }

    /** Reads the picker's new value from the mouse position and notifies {@link #onChange}. */
    protected abstract void applyFromMouse(MouseButtonEvent event);

    @Override
    public final void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.getMessage());
    }
}
