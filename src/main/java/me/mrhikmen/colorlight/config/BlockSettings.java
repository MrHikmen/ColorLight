package me.mrhikmen.colorlight.config;

import net.minecraft.resources.Identifier;

public class BlockSettings {
    public String block;
    public int r;
    public int g;
    public int b;
    public int light;
    public boolean enable;
    public boolean edit;

    /** Parsed form of {@link #block}; transient so Gson neither saves nor reads it. */
    private transient Identifier cachedId;
    private transient String cachedFor;

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
}
