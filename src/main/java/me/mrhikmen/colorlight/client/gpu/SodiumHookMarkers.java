package me.mrhikmen.colorlight.client.gpu;

/**
 * Empty interfaces that the Sodium mixins add to Sodium's classes. {@link ColorLightGpu} looks for them with
 * reflection to learn whether the mixins really applied (they don't when Sodium is missing or has an
 * unsupported version), without ever naming a Sodium class itself.
 */
public final class SodiumHookMarkers {

    /** Added to {@code ShaderChunkRenderer}. */
    public interface Shader {
    }

    /** Added to {@code DefaultChunkRenderer}. */
    public interface Draw {
    }

    /** Added to {@code SodiumWorldRenderer}. */
    public interface Frame {
    }

    private SodiumHookMarkers() {
    }
}
