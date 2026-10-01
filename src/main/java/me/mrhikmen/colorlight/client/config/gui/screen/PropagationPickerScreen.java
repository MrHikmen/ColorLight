package me.mrhikmen.colorlight.client.config.gui.screen;

import me.mrhikmen.colorlight.api.propagation.PropagationMethod;
import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Lets the player pick a propagation method from <b>everything registered right now</b> - the built-in ones, those from
 * Lua files in the active resource packs and those from other mods.
 * <p>
 * Sodium's option widgets are built once, when the game starts, so a fixed slider or list in the options page would
 * only ever show the methods that existed back then (a resource pack applied later would not appear until a restart).
 * This screen reads the registry each time it opens instead.
 */
public class PropagationPickerScreen extends Screen {

    private static final int BUTTON_WIDTH = 230;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int COLUMN_GAP = 10;
    private static final int TOP = 40;
    private static final int BOTTOM_RESERVED = 36;

    private final Screen parent;
    private final Component heading;
    private final Supplier<String> current;
    private final Consumer<String> choose;
    private final Runnable apply;
    private final Predicate<PropagationMethod> allowed;
    private final Identifier fallback;

    /**
     * @param current  the config value now (an id, or a legacy GRID / SMOOTH token)
     * @param choose   stores the picked id in the config
     * @param apply    saves and applies it right away
     * @param allowed  which registered methods to offer
     * @param fallback what an empty or unknown config value means
     */
    public PropagationPickerScreen(Screen parent, Component heading, Supplier<String> current, Consumer<String> choose,
                                   Runnable apply, Predicate<PropagationMethod> allowed, Identifier fallback) {
        super(heading);
        this.parent = parent;
        this.heading = heading;
        this.current = current;
        this.choose = choose;
        this.apply = apply;
        this.allowed = allowed;
        this.fallback = fallback;
    }

    @Override
    protected void init() {
        Identifier selected = PropagationMethodRegistry.parse(this.current.get());
        if (selected == null || !PropagationMethodRegistry.contains(selected))
            selected = this.fallback;

        List<PropagationMethod> methods = new ArrayList<>();
        for (PropagationMethod method : PropagationMethodRegistry.all()) {
            if (this.allowed.test(method) || method.id().equals(selected))
                methods.add(method);
        }

        int rowsPerColumn = Math.max(1, (this.height - TOP - BOTTOM_RESERVED) / (BUTTON_HEIGHT + ROW_GAP));
        int columns = Math.max(1, (methods.size() + rowsPerColumn - 1) / rowsPerColumn);
        int totalWidth = columns * BUTTON_WIDTH + (columns - 1) * COLUMN_GAP;
        int left = (this.width - totalWidth) / 2;

        this.addRenderableWidget(new StringWidget(left, 16, totalWidth, 20, this.heading, this.font));

        for (int i = 0; i < methods.size(); i++) {
            PropagationMethod method = methods.get(i);
            int column = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            boolean isCurrent = method.id().equals(selected);

            Component label = Component.literal(method.displayName());
            if (isCurrent)
                label = Component.empty().append(label).withStyle(ChatFormatting.GREEN);
            if (!isCurrent)
                label = Component.empty().append(label).withStyle(ChatFormatting.YELLOW);

            this.addRenderableWidget(Button.builder(label, button -> {
                        this.choose.accept(method.id().toString());
                        this.apply.run();
                        this.clearWidgets();
                        this.init();
                    })
                    .pos(left + column * (BUTTON_WIDTH + COLUMN_GAP), TOP + row * (BUTTON_HEIGHT + ROW_GAP))
                    .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .pos((this.width - 200) / 2, this.height - 28)
                .size(200, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }
}
