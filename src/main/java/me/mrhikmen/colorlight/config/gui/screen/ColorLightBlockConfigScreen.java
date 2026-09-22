package me.mrhikmen.colorlight.config.gui.screen;

import me.mrhikmen.colorlight.api.propagation.PropagationMethod;
import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.config.BlockSettings;
import me.mrhikmen.colorlight.config.Translatable;
import me.mrhikmen.colorlight.core.scanner.BlockScanner;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public class ColorLightBlockConfigScreen extends Screen {

    private final Screen parent;
    private final BlockSettings entry;
    private final Runnable onSave;
    private final Runnable onApply;

    private ColorPickerMath colorMath;

    private static final int MARGIN = 20;
    private static final int LEFT_COLUMN_WIDTH = 150;
    private static final int DIVIDER_GAP = 16;
    private static final int DIVIDER_WIDTH = 2;
    private static final int ROW_HEIGHT = 24;
    private static final int SQUARE_SIZE = 140;
    private static final int HUE_BAR_WIDTH = 20;
    private static final int HUE_BAR_GAP = 8;
    private static final int DIVIDER_COLOR = 0xFF73EFFF;
    private static final int ACTION_ROW_GAP = 8;
    private static final int ACTION_BUTTON_HEIGHT = 20;

    public ColorLightBlockConfigScreen(Screen parent, BlockSettings entry, Runnable onSave, Runnable onApply) {
        super(Component.translatable("block." + entry.getBlock().toLanguageKey()));
        this.parent = parent;
        this.entry = entry;
        this.onSave = onSave;
        this.onApply = onApply;
        this.colorMath = new ColorPickerMath(entry.r, entry.g, entry.b);
    }

    @Override
    protected void init() {
        int leftX = MARGIN;
        int topY = MARGIN + 20;

        int dividerX = leftX + LEFT_COLUMN_WIDTH + DIVIDER_GAP;
        int rightX = dividerX + DIVIDER_WIDTH + DIVIDER_GAP;

        int contentBottom = topY + ROW_HEIGHT + ROW_HEIGHT + ROW_HEIGHT + 16 + SQUARE_SIZE;
        int dividerHeight = contentBottom - topY + ACTION_ROW_GAP + ACTION_BUTTON_HEIGHT + ACTION_ROW_GAP + ACTION_BUTTON_HEIGHT;

        int iconSize = 64;
        int iconX = leftX + (LEFT_COLUMN_WIDTH - iconSize) / 2;

        DividerWidget iconPlaceholder = new DividerWidget(iconX, topY, iconSize, iconSize, 0xFF222222);
        this.addRenderableWidget(iconPlaceholder);

        StringWidget nameLabel = new StringWidget(
                leftX, topY + iconSize + 12, LEFT_COLUMN_WIDTH, 20,
                this.getTitle(), this.font
        );
        this.addRenderableWidget(nameLabel);

        DividerWidget divider = new DividerWidget(dividerX, topY, DIVIDER_WIDTH, dividerHeight, DIVIDER_COLOR);
        this.addRenderableWidget(divider);

        Checkbox enableCheckbox = Checkbox.builder(Translatable.BLOCK_Tooltip, this.font)
                .pos(rightX, topY)
                .selected(this.entry.enable)
                .onValueChange((checkbox, value) -> {
                    this.entry.enable = value;
                    this.entry.edit = true;
                    this.onSave.run();
                })
                .build();
        this.addRenderableWidget(enableCheckbox);

        int sliderY = topY + ROW_HEIGHT;
        LightRangeSlider lightSlider = new LightRangeSlider(rightX, sliderY, 260, 20, this.entry, this.onSave);
        this.addRenderableWidget(lightSlider);

        int propagationY = sliderY + ROW_HEIGHT;
        Button propagationButton = Button.builder(propagationLabel(), button -> {
            cyclePropagation();
            button.setMessage(propagationLabel());
            this.entry.edit = true;
            this.onSave.run();
        }).pos(rightX, propagationY).size(260, ACTION_BUTTON_HEIGHT).build();
        this.addRenderableWidget(propagationButton);

        int colorLabelY = propagationY + ROW_HEIGHT;
        StringWidget colorLabel = new StringWidget(
                rightX, colorLabelY, 260, 16,
                Translatable.COLORED_LIGHTING, this.font
        );
        this.addRenderableWidget(colorLabel);

        int pickerY = colorLabelY + 16 + 8;

        ColorSquareWidget square = new ColorSquareWidget(
                rightX, pickerY, SQUARE_SIZE, SQUARE_SIZE,
                this.colorMath, this::applyColorFromPicker
        );
        this.addRenderableWidget(square);

        HueBarWidget hueBar = new HueBarWidget(
                rightX + SQUARE_SIZE + HUE_BAR_GAP, pickerY, HUE_BAR_WIDTH, SQUARE_SIZE,
                this.colorMath, this::applyColorFromPicker
        );
        this.addRenderableWidget(hueBar);

        int actionRow1Y = pickerY + SQUARE_SIZE + ACTION_ROW_GAP;

        Button resetButton = Button.builder(Translatable.RESET_TO_DEFAULT, button -> this.resetToDefault())
                .pos(rightX, actionRow1Y)
                .size(260, ACTION_BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(resetButton);

        int actionRow2Y = actionRow1Y + ACTION_BUTTON_HEIGHT + ACTION_ROW_GAP;
        int actionButtonWidth = (260 - ACTION_ROW_GAP) / 2;

        Button applyButton = Button.builder(Translatable.APPLY, button -> this.onApply.run())
                .pos(rightX, actionRow2Y)
                .size(actionButtonWidth, ACTION_BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(applyButton);

        Button doneButton = Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .pos(rightX + actionButtonWidth + ACTION_ROW_GAP, actionRow2Y)
                .size(actionButtonWidth, ACTION_BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(doneButton);
    }

    private void applyColorFromPicker() {
        int[] rgb = this.colorMath.toRgb();
        this.entry.r = rgb[0];
        this.entry.g = rgb[1];
        this.entry.b = rgb[2];
        this.entry.edit = true;
        this.onSave.run();
    }

    /** Every choice the propagation button can land on: "engine default" (null) followed by every registered method. */
    private static List<Identifier> propagationChoices() {
        List<Identifier> ids = new ArrayList<>();
        ids.add(null);
        for (PropagationMethod method : PropagationMethodRegistry.all()) {
            ids.add(method.id());
        }
        return ids;
    }

    private Component propagationLabel() {
        Identifier id = this.entry.getPropagationId();
        Component value;
        if (id == null) {
            value = Translatable.BLOCK_PROPAGATION_DEFAULT;
        } else {
            PropagationMethod method = PropagationMethodRegistry.get(id);
            value = Component.literal((method != null) ? method.displayName() : id.toString());
        }
        return Component.empty().append(Translatable.BLOCK_PROPAGATION).append(Component.literal(": ")).append(value);
    }

    /** Advances this block's propagation setting to the next registered method, wrapping back to "engine default". */
    private void cyclePropagation() {
        List<Identifier> choices = propagationChoices();
        int index = choices.indexOf(this.entry.getPropagationId());
        Identifier next = choices.get((index + 1) % choices.size());
        this.entry.propagation = (next != null) ? next.toString() : "";
    }

    private void resetToDefault() {
        BlockScanner.resetToDefault(this.entry);

        this.colorMath = new ColorPickerMath(this.entry.r, this.entry.g, this.entry.b);
        this.clearWidgets();
        this.init();

        this.onSave.run();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    private static class LightRangeSlider extends AbstractSliderButton {

        private static final int MIN = 1;
        private static final int MAX = 32;

        private final BlockSettings entry;
        private final Runnable onSave;

        LightRangeSlider(int x, int y, int width, int height, BlockSettings entry, Runnable onSave) {
            super(x, y, width, height, Component.empty(), toNormalized(entry.light));
            this.entry = entry;
            this.onSave = onSave;
            this.updateMessage();
        }

        private static double toNormalized(int real) {
            return (real - MIN) / (double) (MAX - MIN);
        }

        private int toReal() {
            return MIN + (int) Math.round(this.value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.empty().append(Translatable.LIGHT_RANGE).append(Component.literal(": " + toReal())));
        }

        @Override
        protected void applyValue() {
            this.entry.light = toReal();
            this.entry.edit = true;
            this.onSave.run();
        }
    }
}