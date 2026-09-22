package me.mrhikmen.colorlight.core.light.propagation;

import java.util.Locale;

public enum ColorLightPropagationMode {
    GRID,
    SMOOTH;

    public static ColorLightPropagationMode fromConfigString(String raw) {
        if (raw == null)
            return GRID;
        try {
            return ColorLightPropagationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GRID;
        }
    }
}
