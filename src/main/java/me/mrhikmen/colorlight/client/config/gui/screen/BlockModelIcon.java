package me.mrhikmen.colorlight.client.config.gui.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws a block icon by hand from the resource pack, following the model json: blockstate -> model (with "parent"
 * chain) -> "elements" (from/to, per-face uv/texture/rotation, element rotation) -> "display.gui" transform. Every
 * pixel is ray-cast through the elements, so any cuboid model (slabs, stairs, fences, torches...) gets its real shape.
 * Needs no ItemStack, so it works without a loaded world. Biome tints are not applied.
 */
final class BlockModelIcon {

    private BlockModelIcon() {}

    private static final String[] DIRS = {"down", "up", "north", "south", "west", "east"};
    private static final double[][] NORMALS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    private static final class Face {
        int dir; String texture; double[] uv; int rotation;
    }

    private static final class Element {
        double[] from, to;
        Face[] faces = new Face[6];
        boolean rotated; char axis; double angle; double[] origin;
    }

    private static final class Chain {
        final Map<String, String> textures = new HashMap<>();
        JsonArray elements;
        JsonObject gui;
    }

    /** Blocks that are simply a full cube of their own texture (sea lantern = "морской фонарь"). */
    private static final java.util.Set<String> CUBE_OVERRIDES = java.util.Set.of("sea_lantern");

    /** Pixels plus whether they came from a fallback (no usable model) rather than the block's own model. */
    record Result(int[] pixels, boolean fallback) {}

    /** @return the icon, or null if nothing at all could be drawn for the block. */
    static Result build(Identifier blockId, int size) {
        try {
            // full cubes drawn as a plain textured cube: the model's geometry for these does not come out right here
            if (CUBE_OVERRIDES.contains(blockId.getPath())) {
                Result cube = fallback(blockId, size);
                if (cube != null) return cube;
            }
            String model = firstModelOf(blockId);
            if (model != null) {
                Chain chain = new Chain();
                collect(model, chain, 0);
                if (chain.elements != null && !chain.elements.isEmpty()) {
                    java.util.List<Element> elements = new java.util.ArrayList<>();
                    for (JsonElement e : chain.elements) elements.add(parseElement(e.getAsJsonObject()));
                    double[] rot = {30, 225, 0}, trans = {0, 0, 0}, scale = {0.625, 0.625, 0.625};
                    if (chain.gui != null) {
                        if (chain.gui.has("rotation")) rot = arr(chain.gui.getAsJsonArray("rotation"));
                        if (chain.gui.has("translation")) trans = arr(chain.gui.getAsJsonArray("translation"));
                        if (chain.gui.has("scale")) scale = arr(chain.gui.getAsJsonArray("scale"));
                    }
                    int[] out = render(size, elements, chain.textures, new HashMap<>(), rot, trans, scale);
                    if (hasPixels(out)) return new Result(out, false);
                }
            }
            Result fb = fallback(blockId, size);
            return fb;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasPixels(int[] px) {
        for (int c : px) if (c != 0) return true;
        return false;
    }

    /**
     * Blocks without a cuboid model: lava (no blockstate at all) and builtin-entity blocks such as the ender chest
     * (blockstate points to "builtin/entity"). Lava gets a cube of its own texture; the ender chest gets a chest-shaped
     * box textured from the chest entity texture (vanilla 64x64 chest UV layout).
     */
    private static Result fallback(Identifier blockId, int size) throws Exception {
        Map<String, int[]> cache = new HashMap<>();
        java.util.List<Element> elements = new java.util.ArrayList<>();
        Map<String, String> textures = new HashMap<>();
        double[] rot = {30, 225, 0}, trans = {0, 0, 0}, scale = {0.625, 0.625, 0.625};

        if (blockId.getPath().equals("ender_chest")) {
            textures.put("c", "entity/chest/ender");
            // vanilla chest UV is in 64px units; the sampler works on 0..16, so divide by 4
            elements.add(box(1, 0, 1, 15, 10, 15, 4,
                    new double[]{14, 33, 28, 43}, new double[]{14, 19, 28, 33}, new double[]{28, 19, 42, 33},
                    new double[]{0, 33, 14, 43}, new double[]{28, 33, 42, 43}, new double[]{42, 33, 56, 43}));
            elements.add(box(1, 10, 1, 15, 15, 15, 4,
                    new double[]{14, 14, 28, 19}, new double[]{14, 0, 28, 14}, new double[]{28, 0, 42, 14},
                    new double[]{0, 14, 14, 19}, new double[]{28, 14, 42, 19}, new double[]{42, 14, 56, 19}));
        } else {
            // first existing block texture: "<name>" or "<name>_still" (lava is lava_still.png)
            String found = null;
            for (String cand : new String[]{"block/" + blockId.getPath(), "block/" + blockId.getPath() + "_still"}) {
                if (load(cand) != null) { found = cand; break; }
            }
            if (found == null) return null;
            textures.put("c", found);
            elements.add(box(0, 0, 0, 16, 16, 16, 1, null, null, null, null, null, null));
        }
        if (elements.isEmpty()) return null;

        int[] out = render(size, elements, textures, cache, rot, trans, scale);
        return hasPixels(out) ? new Result(out, true) : null;
    }

    /**
     * A box whose faces all use texture "#c". uv arrays are given in 0..64 units and divided by {@code uvDiv};
     * null arrays fall back to the full texture (used for the lava cube).
     */
    private static Element box(double x1, double y1, double z1, double x2, double y2, double z2, double uvDiv,
                               double[] south, double[] up, double[] down, double[] east, double[] west, double[] north) {
        Element e = new Element();
        e.from = new double[]{x1, y1, z1};
        e.to = new double[]{x2, y2, z2};
        double[][] uvs = {down, up, north, south, west, east};
        for (int d = 0; d < 6; d++) {
            Face f = new Face();
            f.dir = d;
            f.texture = "#c";
            f.rotation = 0;
            double[] uv = uvs[d];
            if (uv == null) {
                f.uv = new double[]{0, 0, 16, 16};
            } else {
                f.uv = new double[]{uv[0] / uvDiv, uv[1] / uvDiv, uv[2] / uvDiv, uv[3] / uvDiv};
            }
            e.faces[d] = f;
        }
        return e;
    }

    // ---- json -----------------------------------------------------------------------------------------------------

    private static JsonObject readJson(Identifier id) throws Exception {
        var res = Minecraft.getInstance().getResourceManager().getResource(id);
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Identifier id(String ns, String path) {
        return Identifier.tryParse(ns + ":" + path);
    }

    private static Identifier parse(String s) {
        return Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
    }

    private static String firstModelOf(Identifier blockId) throws Exception {
        JsonObject state = readJson(id(blockId.getNamespace(), "blockstates/" + blockId.getPath() + ".json"));
        if (state == null) return null;
        if (state.has("variants")) {
            JsonObject variants = state.getAsJsonObject("variants");
            String unlit = null;
            for (var e : variants.entrySet()) {
                String m = modelFrom(e.getValue());
                if (m == null) continue;
                // prefer the "lit" variant (campfire, furnace...): the unlit one has no flames
                if (!e.getKey().contains("lit=false")) return m;
                if (unlit == null) unlit = m;
            }
            if (unlit != null) return unlit;
        }
        if (state.has("multipart")) {
            for (JsonElement part : state.getAsJsonArray("multipart")) {
                JsonObject obj = part.getAsJsonObject();
                if (obj.has("apply")) {
                    String m = modelFrom(obj.get("apply"));
                    if (m != null) return m;
                }
            }
        }
        return null;
    }

    private static String modelFrom(JsonElement el) {
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            return arr.isEmpty() ? null : modelFrom(arr.get(0));
        }
        JsonObject o = el.getAsJsonObject();
        return o.has("model") ? o.get("model").getAsString() : null;
    }

    /** Child values win over parent values: textures per key, the first "elements" and the first "display.gui". */
    private static void collect(String model, Chain out, int depth) throws Exception {
        if (depth > 10) return;
        Identifier mid = parse(model);
        JsonObject json = readJson(id(mid.getNamespace(), "models/" + mid.getPath() + ".json"));
        if (json == null) return;
        if (json.has("textures")) {
            for (var e : json.getAsJsonObject("textures").entrySet()) {
                if (e.getValue().isJsonPrimitive()) out.textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
            }
        }
        if (out.elements == null && json.has("elements")) out.elements = json.getAsJsonArray("elements");
        if (out.gui == null && json.has("display") && json.getAsJsonObject("display").has("gui")) {
            out.gui = json.getAsJsonObject("display").getAsJsonObject("gui");
        }
        if (json.has("parent")) collect(json.get("parent").getAsString(), out, depth + 1);
    }

    private static double[] arr(JsonArray a) {
        double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsDouble();
        return r;
    }

    private static Element parseElement(JsonObject o) {
        Element e = new Element();
        e.from = arr(o.getAsJsonArray("from"));
        e.to = arr(o.getAsJsonArray("to"));
        if (o.has("rotation")) {
            JsonObject r = o.getAsJsonObject("rotation");
            e.angle = r.get("angle").getAsDouble();
            e.axis = r.get("axis").getAsString().charAt(0);
            e.origin = r.has("origin") ? arr(r.getAsJsonArray("origin")) : new double[]{8, 8, 8};
            e.rotated = e.angle != 0;
        }
        JsonObject faces = o.has("faces") ? o.getAsJsonObject("faces") : new JsonObject();
        for (int d = 0; d < 6; d++) {
            if (!faces.has(DIRS[d])) continue;
            JsonObject f = faces.getAsJsonObject(DIRS[d]);
            Face face = new Face();
            face.dir = d;
            face.texture = f.get("texture").getAsString();
            face.uv = f.has("uv") ? arr(f.getAsJsonArray("uv")) : defaultUv(d, e.from, e.to);
            face.rotation = f.has("rotation") ? f.get("rotation").getAsInt() : 0;
            e.faces[d] = face;
        }
        return e;
    }

    /** Same defaults as the game uses when a face has no "uv". */
    private static double[] defaultUv(int d, double[] f, double[] t) {
        return switch (d) {
            case 0 -> new double[]{f[0], 16 - t[2], t[0], 16 - f[2]};
            case 1 -> new double[]{f[0], f[2], t[0], t[2]};
            case 2 -> new double[]{16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case 3 -> new double[]{f[0], 16 - t[1], t[0], 16 - f[1]};
            case 4 -> new double[]{f[2], 16 - t[1], t[2], 16 - f[1]};
            default -> new double[]{16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
        };
    }

    // ---- textures -------------------------------------------------------------------------------------------------

    private static int[] texture(Map<String, String> textures, Map<String, int[]> cache, String ref) {
        for (int i = 0; ref != null && ref.startsWith("#") && i < 10; i++) ref = textures.get(ref.substring(1));
        if (ref == null || ref.startsWith("#")) return null;
        if (cache.containsKey(ref)) return cache.get(ref);
        int[] px = load(ref);
        cache.put(ref, px);
        return px;
    }

    /** w*w ARGB pixels (the first frame if the texture is an animated strip). */
    private static int[] load(String ref) {
        try {
            Identifier tid = parse(ref);
            var res = Minecraft.getInstance().getResourceManager()
                    .getResource(id(tid.getNamespace(), "textures/" + tid.getPath() + ".png"));
            if (res.isEmpty()) return null;
            try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                int w = img.getWidth();
                int[] px = new int[w * w];
                for (int y = 0; y < w; y++) {
                    for (int x = 0; x < w; x++) px[y * w + x] = img.getPixel(x, y);
                }
                return px;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- ray casting ----------------------------------------------------------------------------------------------

    /** Rotation about one axis, counter-clockwise, degrees. */
    private static double[] rotAxis(char axis, double deg, double[] v) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        return switch (axis) {
            case 'x' -> new double[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
            case 'y' -> new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
            default -> new double[]{v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]};
        };
    }

    /** The gui transform's rotation: X, then Y, then Z are applied... as Quaternionf.rotationXYZ does (Z first). */
    private static double[] rotXYZ(double[] r, double[] v) {
        return rotAxis('x', r[0], rotAxis('y', r[1], rotAxis('z', r[2], v)));
    }

    private static double[] rotXYZInv(double[] r, double[] v) {
        return rotAxis('z', -r[2], rotAxis('y', -r[1], rotAxis('x', -r[0], v)));
    }

    private static int[] render(int size, java.util.List<Element> elements, Map<String, String> textures,
                                Map<String, int[]> cache, double[] rot, double[] trans, double[] scale) {
        int[] out = new int[size * size];
        double[] t = {trans[0] / 16.0, trans[1] / 16.0, trans[2] / 16.0};
        double[] dirModel = rotXYZInv(rot, new double[]{0, 0, 1});
        for (int i = 0; i < 3; i++) dirModel[i] = dirModel[i] / scale[i] * 16.0; // model units per view unit

        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double qx = (px + 0.5) / size - 0.5;
                double qy = -((py + 0.5) / size - 0.5);
                double[] p0 = rotXYZInv(rot, new double[]{qx - t[0], qy - t[1], -t[2]});
                for (int i = 0; i < 3; i++) p0[i] = (p0[i] / scale[i] + 0.5) * 16.0;

                double bestT = -Double.MAX_VALUE;
                int bestColor = 0;
                for (Element e : elements) {
                    double[] o = p0.clone(), d = dirModel.clone();
                    if (e.rotated) {
                        double[] rel = {o[0] - e.origin[0], o[1] - e.origin[1], o[2] - e.origin[2]};
                        rel = rotAxis(e.axis, -e.angle, rel);
                        o = new double[]{rel[0] + e.origin[0], rel[1] + e.origin[1], rel[2] + e.origin[2]};
                        d = rotAxis(e.axis, -e.angle, d);
                    }
                    double tEnter = -Double.MAX_VALUE, tExit = Double.MAX_VALUE;
                    int exitAxis = -1;
                    boolean miss = false;
                    for (int ax = 0; ax < 3; ax++) {
                        double lo = Math.min(e.from[ax], e.to[ax]), hi = Math.max(e.from[ax], e.to[ax]);
                        if (Math.abs(d[ax]) < 1e-9) {
                            if (o[ax] < lo || o[ax] > hi) { miss = true; break; }
                            continue;
                        }
                        double t1 = (lo - o[ax]) / d[ax], t2 = (hi - o[ax]) / d[ax];
                        double tn = Math.min(t1, t2), tx = Math.max(t1, t2);
                        if (tn > tEnter) tEnter = tn;
                        if (tx < tExit) { tExit = tx; exitAxis = ax; }
                    }
                    if (miss || exitAxis < 0 || tEnter > tExit || tExit <= bestT) continue;

                    // visible surface = where the ray leaves the box towards the viewer (+t)
                    int faceDir = switch (exitAxis) {
                        case 0 -> d[0] > 0 ? 5 : 4;
                        case 1 -> d[1] > 0 ? 1 : 0;
                        default -> d[2] > 0 ? 3 : 2;
                    };
                    // a plane (zero thickness) usually has only one side defined: the game draws it from both sides
                    Face face = e.faces[faceDir];
                    if (face == null) {
                        faceDir ^= 1; // opposite face: down<->up, north<->south, west<->east
                        face = e.faces[faceDir];
                    }
                    if (face == null) continue;
                    int[] tex = texture(textures, cache, face.texture);
                    if (tex == null) continue;

                    double hx = o[0] + d[0] * tExit, hy = o[1] + d[1] * tExit, hz = o[2] + d[2] * tExit;
                    double[] fr = faceFraction(faceDir, e, hx, hy, hz);
                    double fu = fr[0], fv = fr[1];
                    for (int r = 0; r < face.rotation / 90; r++) { double nu = 1 - fv, nv = fu; fu = nu; fv = nv; }
                    double u = face.uv[0] + (face.uv[2] - face.uv[0]) * fu;
                    double v = face.uv[1] + (face.uv[3] - face.uv[1]) * fv;
                    int w = (int) Math.round(Math.sqrt(tex.length));
                    int tx2 = Math.max(0, Math.min(w - 1, (int) Math.floor(u / 16.0 * w)));
                    int ty2 = Math.max(0, Math.min(w - 1, (int) Math.floor(v / 16.0 * w)));
                    int argb = tex[ty2 * w + tx2];
                    if ((argb >>> 24) < 128) continue;

                    double[] n = NORMALS[faceDir].clone();
                    if (e.rotated) n = rotAxis(e.axis, e.angle, n);
                    n = rotXYZ(rot, n);
                    double shade = n[1] > 0.7 ? 1.0 : (n[0] < 0 ? 0.8 : 0.6);
                    int rr = (int) Math.round(((argb >> 16) & 0xFF) * shade);
                    int gg = (int) Math.round(((argb >> 8) & 0xFF) * shade);
                    int bb = (int) Math.round((argb & 0xFF) * shade);
                    bestT = tExit;
                    bestColor = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                }
                out[py * size + px] = bestColor;
            }
        }
        return out;
    }

    /** Position on the face as 0..1 fractions along the face's u and v directions (before uv/rotation are applied). */
    private static double[] faceFraction(int dir, Element e, double x, double y, double z) {
        double x1 = Math.min(e.from[0], e.to[0]), x2 = Math.max(e.from[0], e.to[0]);
        double y1 = Math.min(e.from[1], e.to[1]), y2 = Math.max(e.from[1], e.to[1]);
        double z1 = Math.min(e.from[2], e.to[2]), z2 = Math.max(e.from[2], e.to[2]);
        double fx = frac(x, x1, x2), fy = frac(y, y1, y2), fz = frac(z, z1, z2);
        return switch (dir) {
            case 0 -> new double[]{fx, 1 - fz};     // down
            case 1 -> new double[]{fx, fz};         // up
            case 2 -> new double[]{1 - fx, 1 - fy}; // north
            case 3 -> new double[]{fx, 1 - fy};     // south
            case 4 -> new double[]{fz, 1 - fy};     // west
            default -> new double[]{1 - fz, 1 - fy}; // east
        };
    }

    private static double frac(double v, double lo, double hi) {
        return hi - lo < 1e-9 ? 0 : Math.max(0, Math.min(1, (v - lo) / (hi - lo)));
    }
}