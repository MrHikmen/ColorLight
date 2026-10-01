package me.mrhikmen.colorlight.api.propagation;

import me.mrhikmen.colorlight.client.core.light.propagation.TablePropagator;
import me.mrhikmen.colorlight.client.lua.ScriptedPropagationMethod;

import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where {@link PropagationMethod}s live: the single place the engine, the config, the GUI and every addon agree on what
 * {@code "colorlight:grid"}, {@code "colorlight:smooth"} or {@code "colorlight:beam"} mean.
 * <p>
 * Most entries come from Lua files in resource packs and are (re)filled on every resource reload
 * ({@link #clearScripted()} + {@link #register}); methods a mod registered from Java are kept across reloads.
 */
public final class PropagationMethodRegistry {

    /** Id of the diamond-shaped method. Its definition is {@code assets/colorlight/propagation/grid.lua} in the mod's own pack. */
    public static final Identifier GRID = Identifier.fromNamespaceAndPath("colorlight", "grid");
    /** Id of the round method. Its definition is {@code assets/colorlight/propagation/smooth.lua} in the mod's own pack. */
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

    /** Drops every method that came from a Lua file (or the built-in fallback); Java-registered ones stay. */
    public static synchronized void clearScripted() {
        METHODS.values().removeIf(m -> m instanceof ScriptedPropagationMethod);
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
     * The table-driven kernel of method {@code id} for the given decay, or - when {@code id} is unknown or not
     * table-driven - of {@code fallback}, then of {@link #SMOOTH}, then of {@link #GRID}. {@code null} only if
     * nothing at all is registered.
     */
    public static TablePropagator tableFor(Identifier id, Identifier fallback, float decayPerOpacityUnit) {
        for (Identifier candidate : new Identifier[]{id, fallback, SMOOTH, GRID}) {
            PropagationMethod method = get(candidate);
            if (method == null)
                continue;
            TablePropagator table = method.createTable(decayPerOpacityUnit);
            if (table != null)
                return table;
        }
        return null;
    }

    /**
     * Parses a config or API value into an id. Accepts a full {@code namespace:path} id (e.g.
     * {@code "colorlight:beam"}) and, for configs written before methods had ids at all, the bare legacy
     * tokens {@code GRID}/{@code SMOOTH} (case-insensitive). Does <b>not</b> check the id is actually
     * registered - callers resolve that separately so a config written with a pack's method still
     * parses cleanly if that pack is temporarily disabled.
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
