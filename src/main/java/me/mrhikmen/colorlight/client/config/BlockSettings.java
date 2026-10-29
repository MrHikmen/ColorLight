package me.mrhikmen.colorlight.client.config;

import me.mrhikmen.colorlight.client.core.light.propagation.PropagationMethodRegistry;

import net.minecraft.resources.Identifier;

public class BlockSettings {
    public String block;
    public int r;
    public int g;
    public int b;
    public int light;
    public boolean enable;
    public boolean edit;
    public String propagation = "";

    private transient Identifier cachedId;
    private transient String cachedFor;

    private transient Identifier cachedPropagationId;
    private transient String cachedPropagationFor;

    public BlockSettings(Identifier block, int light, boolean enable) {
        this.block = String.valueOf(block);
        this.light = light;
        this.enable = enable;
    }

    public BlockSettings copy() {
        BlockSettings c = new BlockSettings(getBlock(), light, enable);
        c.r = r;
        c.g = g;
        c.b = b;
        c.edit = edit;
        c.propagation = propagation;
        return c;
    }

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
