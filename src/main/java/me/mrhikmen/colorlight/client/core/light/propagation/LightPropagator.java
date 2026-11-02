package me.mrhikmen.colorlight.client.core.light.propagation;

/**
 * A model for how light of one colour spreads outwards from cells that already hold it.
 *
 * @see TablePropagator the data-driven kernel behind every propagation method (grid, smooth, and any JSON file
 *      a resource pack supplies)
 */
public interface LightPropagator {

    /**
     * Spreads light of the colour with palette id {@code id} from the seeds until nothing brighter can be reached.
     * <p>
     * Only this colour is followed: what other colours left in the cells is neither carried on nor compared against.
     * Because brightness only ever falls, the result of several seeds is the per-cell maximum of what each would give
     * alone, whatever the order they are visited in - so seeds can be split between passes (or threads) freely.
     * Cells that end up brighter are written through {@code field}.
     *
     * @param seedKeys   cell keys built with {@code PosKey.pack}
     * @param seedLevels the brightness (1..255) each seed starts with
     */
    void spread(int id, long[] seedKeys, int[] seedLevels, int seedCount, LightField field);
}
