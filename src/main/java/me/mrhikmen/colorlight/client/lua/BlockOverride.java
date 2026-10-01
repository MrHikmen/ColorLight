package me.mrhikmen.colorlight.client.lua;

/**
 * What one {@code colorlight.block(...)} call says about a block. Every field is optional: whatever is left out keeps the
 * value the block already has (from the texture scan, another mod, or an earlier script).
 */
public final class BlockOverride {
    public Integer r, g, b;
    public Integer light;
    public String propagation;
    public Boolean enabled;

    /** Later values win, field by field. */
    public void merge(BlockOverride other) {
        if (other.r != null) { r = other.r; g = other.g; b = other.b; }
        if (other.light != null) light = other.light;
        if (other.propagation != null) propagation = other.propagation;
        if (other.enabled != null) enabled = other.enabled;
    }

    public boolean hasColor() {
        return r != null && g != null && b != null;
    }
}
