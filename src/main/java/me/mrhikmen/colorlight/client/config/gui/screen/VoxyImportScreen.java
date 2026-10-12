package me.mrhikmen.colorlight.client.config.gui.screen;

import me.mrhikmen.colorlight.client.compat.voxy.LodLight;
import me.mrhikmen.colorlight.client.config.Translatable;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Starts and shows the progress of reading the world's far light out of Voxy ({@link LodLight#startImport}).
 * Has no Voxy imports of its own: everything goes through {@link LodLight}. Closing the screen does not stop the
 * import - it carries on in the background; use the stop button for that.
 */
public class VoxyImportScreen extends Screen {

    private final Screen parent;
    private StringWidget status;
    private Button action;

    public VoxyImportScreen(Screen parent) {
        super(Translatable.VOXY_IMPORT);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        this.addRenderableWidget(new StringWidget(cx - 150, 30, 300, 20, Translatable.VOXY_IMPORT, this.font));
        this.status = this.addRenderableWidget(new StringWidget(cx - 150, 70, 300, 20, Component.empty(), this.font));

        this.action = this.addRenderableWidget(Button.builder(Translatable.VOXY_IMPORT_Start, button -> {
                    if (LodLight.isImporting())
                        LodLight.cancelImport();
                    else
                        LodLight.startImport();
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
        boolean importing = LodLight.isImporting();
        Component text;
        if (importing)
            text = Translatable.VOXY_IMPORT_Progress(LodLight.importDone(), LodLight.importTotal());
        else if (LodLight.hasImported())
            text = Translatable.VOXY_IMPORT_Done;
        else
            text = Translatable.VOXY_IMPORT_Idle;
        this.status.setMessage(text);
        this.action.setMessage(importing ? Translatable.VOXY_IMPORT_Cancel : Translatable.VOXY_IMPORT_Start);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }
}
