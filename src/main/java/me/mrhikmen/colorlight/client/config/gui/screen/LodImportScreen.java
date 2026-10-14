package me.mrhikmen.colorlight.client.config.gui.screen;

import me.mrhikmen.colorlight.client.compat.lod.LodImport;
import me.mrhikmen.colorlight.client.config.Translatable;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Starts and shows the progress of reading the world's far light out of one LOD mod (Voxy or Distant Horizons).
 * Works through {@link LodImport}, so it never loads a class of either mod. Closing the screen does not stop the
 * import - it carries on in the background; use the stop button for that.
 */
public class LodImportScreen extends Screen {

    private final Screen parent;
    private final Component heading;
    private final LodImport source;
    private StringWidget status;
    private Button action;

    public LodImportScreen(Screen parent, Component heading, LodImport source) {
        super(heading);
        this.parent = parent;
        this.heading = heading;
        this.source = source;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        this.addRenderableWidget(new StringWidget(cx - 150, 30, 300, 20, this.heading, this.font));
        this.status = this.addRenderableWidget(new StringWidget(cx - 150, 70, 300, 20, Component.empty(), this.font));

        this.action = this.addRenderableWidget(Button.builder(Translatable.LOD_IMPORT_Start, button -> {
                    if (this.source.running())
                        this.source.cancel();
                    else
                        this.source.start();
                    this.refresh();
                })
                .pos(cx - 100, 100)
                .size(200, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .pos(cx - 100, this.height - 28)
                .size(200, 20)
                .build());
        this.refresh();
    }

    @Override
    public void tick() {
        super.tick();
        this.refresh();
    }

    private void refresh() {
        if (this.status == null)
            return;
        boolean running = this.source.running();
        Component text;
        if (running)
            text = Translatable.LOD_IMPORT_Progress(this.source.done(), this.source.total(), this.source.sources());
        else if (this.source.everDone())
            text = Translatable.LOD_IMPORT_Done(this.source.sources());
        else
            text = Translatable.LOD_IMPORT_Idle;
        this.status.setMessage(text);
        this.action.setMessage(running ? Translatable.LOD_IMPORT_Cancel : Translatable.LOD_IMPORT_Start);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }
}
