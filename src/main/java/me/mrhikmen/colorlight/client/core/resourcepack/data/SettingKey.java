package me.mrhikmen.colorlight.client.core.resourcepack.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * The settings a resource pack's {@code settings.json} may provide, with the config field each one maps to.
 * <p>
 * The pack's values are <b>defaults</b>: they apply to every setting the player has not changed themselves (see
 * {@code ColorLightConfig}), so a pack can ship a sensible light range or tint without taking the sliders away.
 */
public enum SettingKey {
    ENABLE("enable", "ENABLE", Type.BOOL, 0, 0),
    LIGHT_RANGE("light_range", "lightRangeBlocks", Type.INT, 1, 32),
    SMOOTH_LIGHTING("smooth_lighting", "SMOOTH_LIGHTING", Type.BOOL, 0, 0),
    /** Id of the propagation method blocks use unless they name their own, e.g. {@code "colorlight:smooth"}. */
    PROPAGATION("propagation", "PROPAGATION_MODE", Type.STRING, 0, 0),
    /** Id of the propagation method moving (entity) lights use. Must be a table-driven method. */
    DYNAMIC_PROPAGATION("dynamic_propagation", "DYNAMIC_PROPAGATION", Type.STRING, 0, 0),
    TINT_GAMMA("tint_gamma", "TINT_GAMMA", Type.FLOAT, 0, 2),

    BRIGHTNESS_WEIGHT("brightness_weight", "BRIGHTNESS_WEIGHT", Type.INT, 0, 100),
    LOCAL_WEIGHT("local_weight", "LOCAL_WEIGHT", Type.INT, 0, 100),
    REGION_WEIGHT("region_weight", "REGION_WEIGHT", Type.INT, 0, 100),
    ALPHA_WEIGHT("alpha_weight", "ALPHA_WEIGHT", Type.INT, 0, 100),
    ANOMALY_WEIGHT("anomaly_weight", "ANOMALY_WEIGHT", Type.INT, 0, 100),
    SATURATION_WEIGHT("saturation_weight", "SATURATION_WEIGHT", Type.INT, 0, 100),
    GLOWCOLORSCORE_WEIGHT("glowcolorscore_weight", "GLOWCOLORSCORE_WEIGHT", Type.INT, 0, 100),
    WHITEPENALTY_WEIGHT("whitepenalty_weight", "WHITEPENALTY_WEIGHT", Type.INT, 0, 100),

    ENTITY_TRACKING("entity_tracking", "ENTITY_TRACKING_ENABLED", Type.BOOL, 0, 0),
    ENTITY_FOLLOW_RENDER_DISTANCE("entity_follow_render_distance", "ENTITY_CHECK_FOLLOW_RENDER_DISTANCE", Type.BOOL, 0, 0),
    ENTITY_RADIUS_CHUNKS("entity_radius_chunks", "ENTITY_CHECK_RADIUS_CHUNKS", Type.INT, 2, 32);

    public enum Type { BOOL, INT, FLOAT, STRING }

    public final String jsonKey;
    public final String field;
    public final Type type;
    public final double min, max;

    SettingKey(String jsonKey, String field, Type type, double min, double max) {
        this.jsonKey = jsonKey;
        this.field = field;
        this.type = type;
        this.min = min;
        this.max = max;
    }

    public static SettingKey byJsonKey(String key) {
        for (SettingKey k : values())
            if (k.jsonKey.equals(key))
                return k;
        return null;
    }

    /**
     * Converts a JSON value to the Java value for this key (Boolean, Integer, Float or String), clamped to range.
     *
     * @throws IllegalArgumentException if the value has the wrong type
     */
    public Object coerce(JsonElement value) {
        if (value == null || !value.isJsonPrimitive())
            throw new IllegalArgumentException("'" + jsonKey + "' has an invalid value");
        JsonPrimitive p = value.getAsJsonPrimitive();
        switch (type) {
            case BOOL:
                if (!p.isBoolean())
                    throw new IllegalArgumentException("'" + jsonKey + "' must be true or false");
                return p.getAsBoolean();
            case INT:
                if (!p.isNumber())
                    throw new IllegalArgumentException("'" + jsonKey + "' must be a number");
                return (int) Math.max(min, Math.min(max, Math.round(p.getAsDouble())));
            case FLOAT:
                if (!p.isNumber())
                    throw new IllegalArgumentException("'" + jsonKey + "' must be a number");
                return (float) Math.max(min, Math.min(max, p.getAsDouble()));
            default:
                if (!p.isString())
                    throw new IllegalArgumentException("'" + jsonKey + "' must be a string");
                return p.getAsString();
        }
    }
}
