package me.mrhikmen.colorlight.client.compat.lod;

import me.mrhikmen.colorlight.client.ColorLightClient;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DIAGNOSTIC counters for the far light (Voxy/DH). Off unless -Dcolorlight.diag.stats=true; then one log line every 5 s.
 */
public final class LodDiag {

    public static final boolean ENABLED = Boolean.getBoolean("colorlight.diag.stats");

    public static final AtomicLong frames = new AtomicLong();
    public static final AtomicLong fillRequests = new AtomicLong();
    public static final AtomicLong fillBuilds = new AtomicLong();
    public static final AtomicLong buildNanos = new AtomicLong();
    public static final AtomicLong uploads = new AtomicLong();
    public static final AtomicLong uploadBytes = new AtomicLong();
    public static final AtomicLong headerWrites = new AtomicLong();
    public static final AtomicLong columnScans = new AtomicLong();
    public static final AtomicLong versionChanges = new AtomicLong();
    public static final AtomicLong remeshSections = new AtomicLong();
    // light volume (OpenGL path)
    public static final AtomicLong volTileComputes = new AtomicLong();
    public static final AtomicLong volComputeNanos = new AtomicLong();
    public static final AtomicLong volUploadTiles = new AtomicLong();
    public static final AtomicLong volUploadEntries = new AtomicLong();
    public static volatile int volTiles;
    public static volatile long volPending;
    public static volatile long volOverflow;

    private static long lastLog = System.nanoTime();

    private LodDiag() {
    }

    /** Called once per rendered frame on the render thread. */
    public static void frame() {
        if (!ENABLED)
            return;
        frames.incrementAndGet();
        long now = System.nanoTime();
        if (now - lastLog < 5_000_000_000L)
            return;
        lastLog = now;
        long f = frames.getAndSet(0);
        long builds = fillBuilds.getAndSet(0);
        long bn = buildNanos.getAndSet(0);
        long vc = volTileComputes.getAndSet(0);
        long vn = volComputeNanos.getAndSet(0);
        ColorLightClient.LOGGER.info("[ColorLight diag 5s volume] tiles={} pendingGpu={} computes={} avgComputeMs={} uploadTiles={} uploadEntries={} overflow={}",
                volTiles, volPending, vc, vc == 0 ? 0 : String.format("%.2f", vn / 1e6 / vc),
                volUploadTiles.getAndSet(0), volUploadEntries.getAndSet(0), volOverflow);
        ColorLightClient.LOGGER.info("[ColorLight diag 5s] frames={} fillRequests={} builds={} avgBuildMs={} uploads={} uploadMB={} headerWrites={} columnScans={} versionChanges={} remeshSections={}",
                f, fillRequests.getAndSet(0), builds, builds == 0 ? 0 : String.format("%.1f", bn / 1e6 / builds),
                uploads.getAndSet(0), String.format("%.1f", uploadBytes.getAndSet(0) / 1e6),
                headerWrites.getAndSet(0), columnScans.getAndSet(0), versionChanges.getAndSet(0), remeshSections.getAndSet(0));
    }
}
