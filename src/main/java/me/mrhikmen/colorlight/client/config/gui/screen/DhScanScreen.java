package me.mrhikmen.colorlight.client.config.gui.screen;

import me.mrhikmen.colorlight.client.compat.distanthorizons.DhLodLight;
import me.mrhikmen.colorlight.client.config.Translatable;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shows the progress of {@link DhLodLight}'s background scan for coloured light sources in Distant Horizons'
 * saved data, and a button to start a fresh one centred on the player's current position. Has no Distant Horizons
 * imports of its own: everything goes through {@link DhLodLight}. The scan keeps running in the background
 * whether this screen is open or not; closing it does not stop anything.
 */
public class DhScanScreen extends Screen {

    private final Screen parent;
    private StringWidget status;

    public DhScanScreen(Screen parent) {
        super(Translatable.DH_SCAN);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        this.addRenderableWidget(new StringWidget(cx - 150, 30, 300, 20, Translatable.DH_SCAN, this.font));
        this.status = this.addRenderableWidget(new StringWidget(cx - 150, 70, 300, 20, Component.empty(), this.font));

        this.addRenderableWidget(Button.builder(Translatable.DH_SCAN_Start, button -> {
                    DhLodLight.startScan();
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
        Component text;
        if (DhLodLight.isScanning())
            text = Translatable.DH_SCAN_Progress(DhLodLight.scanDone(), DhLodLight.scanTotal(), DhLodLight.sourcesFound());
        else if (DhLodLight.hasScannedOnce())
            text = Translatable.DH_SCAN_Done(DhLodLight.sourcesFound());
        else
            text = Translatable.DH_SCAN_Idle;
        this.status.setMessage(text);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }
}
