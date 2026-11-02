package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.ColorLightClient;

/**
 * Cheap phase timers for the static-light flood, to see where the time of loading light goes before deciding what to
 * speed up. Off unless the game starts with {@code -Dcolorlight.profile=true}; then a summary line is logged about
 * every ten seconds while sources are being added.
 * <p>
 * The counters are plain fields: exact while the engine lock is held, and only approximate for the few lookups made by
 * moving lights, which is fine for a profile.
 */
final class FloodStats {

    static final boolean ON = Boolean.getBoolean("colorlight.profile");

    private static final long REPORT_EVERY_NANOS = 10_000_000_000L;

    private static long placeNanos, floodNanos, groups, sources, opacityLookups, parallelFloods, parallelTasks, parallelNanos;
    private static long lastReport = System.nanoTime();

    /** One block-state lookup that the per-pass memo did not have (see {@link WorldLightField#opacityAt}). */
    static void opacityLookup() {
        opacityLookups++;
    }

    /** One flood that was handed to the worker threads: how many tasks and how long the lock holder waited for them. */
    static void parallel(int tasks, long nanos) {
        parallelFloods++;
        parallelTasks += tasks;
        parallelNanos += nanos;
    }

    static void group(int sourceCount, long placeNs, long floodNs) {
        groups++;
        sources += sourceCount;
        placeNanos += placeNs;
        floodNanos += floodNs;

        long now = System.nanoTime();
        if (now - lastReport < REPORT_EVERY_NANOS)
            return;
        lastReport = now;

        ColorLightClient.LOGGER.info("[ColorLight] flood profile: {} groups / {} sources, placing {} ms, flooding {} ms ({} parallel floods, {} tasks, {} ms of it waiting for workers), {} block-state lookups",
                groups, sources, placeNanos / 1_000_000L, floodNanos / 1_000_000L, parallelFloods, parallelTasks, parallelNanos / 1_000_000L, opacityLookups);
        placeNanos = floodNanos = groups = sources = opacityLookups = parallelFloods = parallelTasks = parallelNanos = 0;
    }

    private FloodStats() {
    }
}
