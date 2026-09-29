package me.mrhikmen.colorlight.client.config;

import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;

import net.minecraft.resources.Identifier;

public class BlockSettings {
    public String block;
    public int r;
    public int g;
    public int b;
    public int light;
    public boolean enable;
    public boolean edit;

    /**
     * Id of the {@link me.mrhikmen.colorlight.api.propagation.PropagationMethod} this block spreads
     * with, e.g. {@code "colorlight:smooth"} or a third-party mod's own id. Blank/absent means
     * "use the engine-wide default" - existing configs without this field keep working unchanged.
     */
    public String propagation = "";

    /** Parsed form of {@link #block}; transient so Gson neither saves nor reads it. */
    private transient Identifier cachedId;
    private transient String cachedFor;

    /** Parsed form of {@link #propagation}; transient so Gson neither saves nor reads it. */
    private transient Identifier cachedPropagationId;
    private transient String cachedPropagationFor;

    public BlockSettings(Identifier block, int light, boolean enable) {
        this.block = String.valueOf(block);
        this.light = light;
        this.enable = enable;
    }

    /** Parsing an Identifier on every call used to dominate config-wide scans; parse once per string value. */
    public Identifier getBlock() {
        String current = block;
        Identifier id = cachedId;
        if (id == null || !current.equals(cachedFor)) {
            id = Identifier.parse(current);
            cachedId = id;
            cachedFor = current;
        }
        return id;
    }

    /**
     * Parsed form of {@link #propagation}, or {@code null} when the block should use the engine's
     * default propagation method (either because the field is blank, or - configs are hand-written
     * and mods come and go - because the id it names isn't currently registered).
     */
    public Identifier getPropagationId() {
        String current = propagation;
        if (current == null || current.isBlank())
            return null;

        if (!current.equals(cachedPropagationFor)) {
            cachedPropagationId = PropagationMethodRegistry.parse(current);
            cachedPropagationFor = current;
        }
        return cachedPropagationId;
    }
}
