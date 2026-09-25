package me.mrhikmen.colorlight.client.core.render;

import me.mrhikmen.colorlight.client.ColorLightClient;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad.MaterialFlags;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

public class TintedBakedModel implements BlockStateModel {

    /** One scratch sampler per mesh worker thread: chunk meshing must not allocate per vertex. */
    private static final ThreadLocal<Sampler> SAMPLERS = ThreadLocal.withInitial(Sampler::new);

    private final BlockStateModel wrapped;

    public TintedBakedModel(BlockStateModel original) {
        this.wrapped = original;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        wrapped.collectParts(random, output);
    }

    @Override
    public Material.Baked particleMaterial() {
        return wrapped.particleMaterial();
    }

    @Override
    public @MaterialFlags int materialFlags() {
        return wrapped.materialFlags();
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {

        ColorLightEngine engine = ColorLightEngineHolder.get();

        // No engine (world closing while meshing) or no light anywhere near this block: every vertex
        // would come out white anyway, so skip all sampling. This is the common case for most blocks.
        if (engine == null || engine.isLightFreeAround(pos.getX(), pos.getY(), pos.getZ())) {
            wrapped.emitQuads(emitter, blockView, pos, state, random, cullTest);
            return;
        }

        final Sampler sampler = SAMPLERS.get();
        sampler.begin(engine, blockView, pos);

        final boolean smooth = ColorLightClient.config.SMOOTH_LIGHTING;

        emitter.pushTransform(quad -> {
            Direction face = quad.lightFace();

            int c0, c1, c2, c3;
            if (face == null) {
                c0 = c1 = c2 = c3 = sampler.ownColor();
            } else if (!smooth) {
                c0 = c1 = c2 = c3 = sampler.flatColor(face);
            } else {
                c0 = sampler.vertexColor(face, quad.x(0), quad.y(0), quad.z(0));
                c1 = sampler.vertexColor(face, quad.x(1), quad.y(1), quad.z(1));
                c2 = sampler.vertexColor(face, quad.x(2), quad.y(2), quad.z(2));
                c3 = sampler.vertexColor(face, quad.x(3), quad.y(3), quad.z(3));
            }

            if ((c0 | c1 | c2 | c3) == 0) {
                quad.color(0, 0xFFFFFFFF);
                quad.color(1, 0xFFFFFFFF);
                quad.color(2, 0xFFFFFFFF);
                quad.color(3, 0xFFFFFFFF);
                return true;
            }

            float daylight = sampler.daylight(face);
            float gamma = ColorLightClient.config.TINT_GAMMA;

            // vertices of a quad very often share the same colour: convert each distinct one once
            int a0 = ColorLightUtil.toArgb(c0, daylight, gamma);
            int a1 = (c1 == c0) ? a0 : ColorLightUtil.toArgb(c1, daylight, gamma);
            int a2 = (c2 == c0) ? a0 : (c2 == c1) ? a1 : ColorLightUtil.toArgb(c2, daylight, gamma);
            int a3 = (c3 == c0) ? a0 : (c3 == c1) ? a1 : (c3 == c2) ? a2 : ColorLightUtil.toArgb(c3, daylight, gamma);

            quad.color(0, a0);
            quad.color(1, a1);
            quad.color(2, a2);
            quad.color(3, a3);

            return true;
        });

        wrapped.emitQuads(emitter, blockView, pos, state, random, cullTest);

        emitter.popTransform();
        sampler.end();
    }

    /**
     * Per-block light sampler. For each face of the block it lazily reads the 3x3 grid of cells in
     * front of that face once (instead of up to 16 separate, allocating lookups per quad) and every
     * vertex averages its 2x2 corner of that grid - the same result as before, computed once per
     * face and without allocating.
     */
    private static final class Sampler {

        private ColorLightEngine engine;
        private BlockAndTintGetter view;
        private int px, py, pz;

        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        private final int[] grid = new int[6 * 9];
        private final boolean[] opaque = new boolean[6 * 9];
        private final int[] flat = new int[6];
        private final float[] daylightByFace = new float[7];

        private int gridMask;
        private int flatMask;
        private int daylightMask;

        private int own;
        private boolean ownKnown;

        void begin(ColorLightEngine engine, BlockAndTintGetter view, BlockPos pos) {
            this.engine = engine;
            this.view = view;
            this.px = pos.getX();
            this.py = pos.getY();
            this.pz = pos.getZ();
            this.gridMask = 0;
            this.flatMask = 0;
            this.daylightMask = 0;
            this.ownKnown = false;
        }

        void end() {
            this.engine = null;
            this.view = null;
        }

        int ownColor() {
            if (!ownKnown) {
                own = engine.getColor(px, py, pz);
                ownKnown = true;
            }
            return own;
        }

        int flatColor(Direction face) {
            int f = face.ordinal();
            if ((flatMask & (1 << f)) == 0) {
                flat[f] = ColorLightUtil.max(ownColor(), engine.getColor(px + face.getStepX(), py + face.getStepY(), pz + face.getStepZ()));
                flatMask |= 1 << f;
            }
            return flat[f];
        }

        float daylight(Direction face) {
            int slot = face == null ? 6 : face.ordinal();
            if ((daylightMask & (1 << slot)) == 0) {
                if (face == null) {
                    cursor.set(px, py, pz);
                } else {
                    cursor.set(px + face.getStepX(), py + face.getStepY(), pz + face.getStepZ());
                }
                daylightByFace[slot] = engine.getDaylightFactor(cursor);
                daylightMask |= 1 << slot;
            }
            return daylightByFace[slot];
        }

        int vertexColor(Direction face, float vx, float vy, float vz) {
            int f = face.ordinal();
            if ((gridMask & (1 << f)) == 0)
                fillGrid(face, f);

            float coordA;
            float coordB;
            switch (face.getAxis()) {
                case X -> { coordA = vy; coordB = vz; }
                case Y -> { coordA = vx; coordB = vz; }
                default -> { coordA = vx; coordB = vy; }
            }

            // grid index 1 is the cell straight in front of the face; a vertex uses the 2x2 block nearest to it
            int a0 = coordA < 0.5f ? 0 : 1;
            int b0 = coordB < 0.5f ? 0 : 1;

            int sumR = 0, sumG = 0, sumB = 0, valid = 0;
            int base = f * 9;
            for (int ia = a0; ia <= a0 + 1; ia++) {
                for (int ib = b0; ib <= b0 + 1; ib++) {
                    int idx = base + ia * 3 + ib;
                    if (opaque[idx])
                        continue;
                    int c = grid[idx];
                    sumR += c & 0xFF;
                    sumG += (c >> 8) & 0xFF;
                    sumB += (c >> 16) & 0xFF;
                    valid++;
                }
            }

            int avg = 0;
            if (valid > 0) {
                avg = ColorLightUtil.pack(
                        Math.round(sumR / (float) valid),
                        Math.round(sumG / (float) valid),
                        Math.round(sumB / (float) valid));
            }
            return ColorLightUtil.max(avg, ownColor());
        }

        private void fillGrid(Direction face, int f) {
            int fx = px + face.getStepX();
            int fy = py + face.getStepY();
            int fz = pz + face.getStepZ();

            int ax, ay, az; // step along axis A
            int bx, by, bz; // step along axis B
            switch (face.getAxis()) {
                case X -> { ax = 0; ay = 1; az = 0; bx = 0; by = 0; bz = 1; }
                case Y -> { ax = 1; ay = 0; az = 0; bx = 0; by = 0; bz = 1; }
                default -> { ax = 1; ay = 0; az = 0; bx = 0; by = 1; bz = 0; }
            }

            int base = f * 9;
            boolean anyLight = false;

            for (int ia = 0; ia < 3; ia++) {
                for (int ib = 0; ib < 3; ib++) {
                    int oa = ia - 1;
                    int ob = ib - 1;
                    int idx = base + ia * 3 + ib;
                    int c = engine.getColor(fx + oa * ax + ob * bx, fy + oa * ay + ob * by, fz + oa * az + ob * bz);
                    grid[idx] = c;
                    anyLight |= c != 0;
                }
            }

            // Opacity only matters for excluding samples from the average; with no light in the whole
            // 3x3 patch every vertex averages to zero regardless, so don't even look at the blocks.
            for (int ia = 0; ia < 3; ia++) {
                for (int ib = 0; ib < 3; ib++) {
                    int oa = ia - 1;
                    int ob = ib - 1;
                    int idx = base + ia * 3 + ib;
                    if (!anyLight) {
                        opaque[idx] = false;
                        continue;
                    }
                    cursor.set(fx + oa * ax + ob * bx, fy + oa * ay + ob * by, fz + oa * az + ob * bz);
                    opaque[idx] = view.getBlockState(cursor).getLightDampening() >= 15;
                }
            }

            gridMask |= 1 << f;
        }
    }
}