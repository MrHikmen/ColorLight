package me.mrhikmen.colorlight.api.propagation;

import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where {@link PropagationMethod}s live. ColorLight's own two shapes (grid/diamond and
 * smooth/circle - see {@link me.mrhikmen.colorlight.api.propagation.builtin.BuiltinPropagationMethods})
 * are registered here exactly the way a third-party mod would register its own, so this registry is
 * the single place the engine, the config and any addon agree on what "grid", "smooth" or
 * "mymod:column" mean.
 * <p>
 * Registration must happen during mod init, before the world (and with it the engine) is created -
 * call {@link #register(PropagationMethod)} from your {@code ClientModInitializer}, the same place
 * {@code BuiltinPropagationMethods.registerAll()} is called from ColorLight's own init.
 */
public final class PropagationMethodRegistry {

    /** Id of the built-in diamond-shaped method (see {@code GridPropagator}). */
    public static final Identifier GRID = Identifier.fromNamespaceAndPath("colorlight", "grid");
    /** Id of the built-in round method (see {@code SmoothPropagator}). */
    public static final Identifier SMOOTH = Identifier.fromNamespaceAndPath("colorlight", "smooth");

    /** Insertion-ordered so a GUI listing methods gets a stable, predictable order. */
    private static final Map<Identifier, PropagationMethod> METHODS = new LinkedHashMap<>();

    public static synchronized void register(PropagationMethod method) {
        if (method == null || method.id() == null)
            throw new IllegalArgumentException("A propagation method and its id must not be null");
        METHODS.put(method.id(), method);
    }

    public static synchronized void unregister(Identifier id) {
        if (id != null)
            METHODS.remove(id);
    }

    public static synchronized PropagationMethod get(Identifier id) {
        return (id != null) ? METHODS.get(id) : null;
    }

    public static synchronized boolean contains(Identifier id) {
        return id != null && METHODS.containsKey(id);
    }

    /** Every registered method, in registration order. Safe to iterate without external locking. */
    public static synchronized Collection<PropagationMethod> all() {
        return List.copyOf(METHODS.values());
    }

    /**
     * Parses a config or API value into an id. Accepts a full {@code namespace:path} id (e.g.
     * {@code "mymod:column"}) and, for configs written before methods had ids at all, the bare legacy
     * tokens {@code GRID}/{@code SMOOTH} (case-insensitive). Does <b>not</b> check the id is actually
     * registered - callers resolve that separately so a config written with an addon's method still
     * parses cleanly if that addon is temporarily missing.
     *
     * @return the parsed id, or {@code null} for a blank value (meaning "use the engine default")
     */
    public static Identifier parse(String raw) {
        if (raw == null)
            return null;

        String trimmed = raw.trim();
        if (trimmed.isEmpty())
            return null;

        switch (trimmed.toUpperCase(Locale.ROOT)) {
            case "GRID":
                return GRID;
            case "SMOOTH":
                return SMOOTH;
            default:
                break;
        }

        try {
            return Identifier.parse(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private PropagationMethodRegistry() {
    }
}
