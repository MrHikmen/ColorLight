package me.mrhikmen.colorlight;

import me.mrhikmen.colorlight.core.Manager;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ColorLightClient implements ClientModInitializer {

    public static final Logger LOGGER =
            LoggerFactory.getLogger("ColorLight");

    @Override
    public void onInitializeClient() {
        new Manager();
    }
}
