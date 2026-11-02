package me.mrhikmen.colorlight.client.core.light.engine;

import me.mrhikmen.colorlight.client.ColorLightClient;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Profile of the other stages light goes through while loading, next to {@link FloodStats}: scanning chunks for sources
 * (scanner threads) and uploading sections to the graphics card (render thread). Off unless the game starts with
 * {@code -Dcolorlight.profile=true}; then one line per stage is logged about every ten seconds while there is work.
 */
public final class LoadStats {

    public static final boolean ON = Boolean.getBoolean("colorlight.profile");

    private static final long REPORT_EVERY_NANOS = 10_000_000_000L;

    private static final AtomicLong scannedChunks = new AtomicLong(), scanNanos = new AtomicLong(), foundSources = new AtomicLong();
    private static long uploadedSections, uploadNanos, uploadTicks, maxBacklog;
    private static volatile long lastReport = System.nanoTime();

    /** A chunk was scanned for light sources (any scanner thread). */
    public static void scan(long nanos, int sources) {
        scannedChunks.incrementAndGet();
        scanNanos.addAndGet(nanos);
        foundSources.addAndGet(sources);
    }

    /** One client tick of uploading light sections to the graphics card (render thread). */
    public static void upload(int sections, long nanos, int backlog) {
        uploadedSections += sections;
        uploadNanos += nanos;
        uploadTicks++;
        maxBacklog = Math.max(maxBacklog, backlog);
        report();
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - lastReport < REPORT_EVERY_NANOS)
            return;
        lastReport = now;

        if (uploadedSections > 0) {
            ColorLightClient.LOGGER.info("[ColorLight] GPU upload profile: {} sections in {} ms on the render thread over {} ticks, most waiting at once {}",
                    uploadedSections, uploadNanos / 1_000_000L, uploadTicks, maxBacklog);
        }
        long chunks = scannedChunks.getAndSet(0);
        if (chunks > 0) {
            ColorLightClient.LOGGER.info("[ColorLight] scan profile: {} chunks in {} ms (all scanner threads together), {} sources found",
                    chunks, scanNanos.getAndSet(0) / 1_000_000L, foundSources.getAndSet(0));
        }
        uploadedSections = uploadNanos = uploadTicks = maxBacklog = 0;
    }

    private LoadStats() {
    }
}
