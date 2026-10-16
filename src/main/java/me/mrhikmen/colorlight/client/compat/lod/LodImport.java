package me.mrhikmen.colorlight.client.compat.lod;

/**
 * What the shared "import light for LODs" screen needs from one LOD mod (Voxy, Distant Horizons). Implemented by the
 * mods' owner classes without any mod class in the signature, so the screen itself never loads a mod class.
 */
public interface LodImport {

    /** Reads the world's light sources again (slowly, in the background). False when there is no world to read. */
    boolean start();

    void cancel();

    boolean running();

    /** Units of work done / in total in the running (or last) pass. */
    int done();

    int total();

    /** True once a pass has finished for this world. */
    boolean everDone();

    /** Light sources known for the world (shared by all LOD mods). */
    int sources();
}
