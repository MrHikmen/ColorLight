package me.mrhikmen.colorlight.client.core.light;

/**
 * The knobs of the light core that the "Debug" settings page changes while the game runs. Plain volatile fields so the
 * hot code (cell colour, flood scheduling, the scanner pool) reads them without a lock; {@code ColorLightConfig} writes
 * them. Each starts at the value of its {@code -Dcolorlight.*} system property (or the built-in default), so the old way of
 * setting them still works until the config file says otherwise.
 * <p>
 * Settings about threads use 0 for "decide from the number of CPU threads".
 */
public final class LightTuning {

    // ---- white light ----------------------------------------------------------------------------------------------

    /** A coloured light is laid over a white one where they meet (see {@code LightCell}). {@code -Dcolorlight.white.loses}. */
    public static volatile boolean whiteOverlay = Boolean.parseBoolean(System.getProperty("colorlight.white.loses", "true"));
    /** A hue counts as white when its weakest channel is within this many (0..60) of 255. {@code -Dcolorlight.white.spread}. */
    public static volatile int whiteSpread = clamp(Integer.getInteger("colorlight.white.spread", 20), 0, 60);
    /** A colour covers white completely once it is this percent (10..100) as bright as the white. {@code -Dcolorlight.white.cover} is 100 / this. */
    public static volatile int whiteCoverPercent = clamp(100 / Math.max(1, Integer.getInteger("colorlight.white.cover", 2)), 10, 100);
    /** A full cell leaves white out before any colour. {@code -Dcolorlight.white.evicts}. */
    public static volatile boolean whiteEvicts = Boolean.parseBoolean(System.getProperty("colorlight.white.evicts", "false"));

    // ---- light engine ---------------------------------------------------------------------------------------------

    /** Threads that spread light in one batch, the apply thread included; 0 = a quarter of the CPU's threads. {@code -Dcolorlight.flood.threads}. */
    public static volatile int floodThreads = Math.max(0, Integer.getInteger("colorlight.flood.threads", 0));
    /** Threads that look for light sources in loaded chunks; 0 = a quarter of the CPU's threads, at most 2. {@code -Dcolorlight.scan.threads}. */
    public static volatile int scanThreads = Math.max(0, Integer.getInteger("colorlight.scan.threads", 0));
    /** A chunk column taller than this is flooded in bands of this height; 0 = never cut. {@code -Dcolorlight.flood.band}. */
    public static volatile int floodBand = Math.max(0, Integer.getInteger("colorlight.flood.band", 64));
    /** Sources placed and flooded under one lock hold. {@code -Dcolorlight.batch.group}. */
    public static volatile int floodGroup = clamp(Integer.getInteger("colorlight.batch.group", 1024), 16, 65536);
    /** Most seeds of one colour in one chunk column flooded in one go. {@code -Dcolorlight.flood.maxseeds}. */
    public static volatile int floodMaxSeeds = clamp(Integer.getInteger("colorlight.flood.maxseeds", 8192), 64, 65536);
    /** A batch with fewer seeds than this is flooded on one thread. {@code -Dcolorlight.flood.parallelseeds}. */
    public static volatile int floodParallelMinSeeds = clamp(Integer.getInteger("colorlight.flood.parallelseeds", 128), 1, 65536);
    /** Sources found in chunks that wait for the apply thread before they are flooded right away. {@code -Dcolorlight.flush.sources}. */
    public static volatile int sourceFlushSize = clamp(Integer.getInteger("colorlight.flush.sources", 512), 16, 65536);

    private LightTuning() {
    }

    public static int cpuThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** Threads that flood one batch (apply thread included): the setting, or a quarter of the CPU's threads, at least 1. */
    public static int resolveFloodThreads() {
        int n = floodThreads;
        return n > 0 ? n : autoFloodThreads();
    }

    /** What "Auto" means for flood threads. */
    public static int autoFloodThreads() {
        return Math.max(1, cpuThreads() / 4);
    }

    /** What "Auto" means for scanner threads. */
    public static int autoScanThreads() {
        return Math.max(1, Math.min(2, cpuThreads() / 4));
    }

    /** Threads that scan chunks: the setting, or a quarter of the CPU's threads, 1..2. */
    public static int resolveScanThreads() {
        int n = scanThreads;
        return n > 0 ? n : autoScanThreads();
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
