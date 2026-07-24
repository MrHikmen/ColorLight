package me.mrhikmen.colorlight.render;

import me.mrhikmen.colorlight.light.ColorLightEngine;
import me.mrhikmen.colorlight.light.ColorLightUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

final class SmoothLightSampler {

    static int sample(ColorLightEngine engine, BlockPos pos, Direction face, float vx, float vy, float vz) {

        BlockPos facePos = (face != null) ? pos.relative(face) : pos;

        if (face == null) {
            return ColorLightUtil.max(engine.getColor(pos), engine.getColor(facePos));
        }

        Direction.Axis axisA;
        Direction.Axis axisB;

        switch (face.getAxis()) {
            case X -> { axisA = Direction.Axis.Y; axisB = Direction.Axis.Z; }
            case Y -> { axisA = Direction.Axis.X; axisB = Direction.Axis.Z; }
            default -> { axisA = Direction.Axis.X; axisB = Direction.Axis.Y; }
        }

        float coordA = axisCoord(axisA, vx, vy, vz);
        float coordB = axisCoord(axisB, vx, vy, vz);

        int[] offsetsA = (coordA < 0.5f) ? new int[]{-1, 0} : new int[]{0, 1};
        int[] offsetsB = (coordB < 0.5f) ? new int[]{-1, 0} : new int[]{0, 1};

        int sumR = 0, sumG = 0, sumB = 0;

        for (int oa : offsetsA) {
            for (int ob : offsetsB) {
                BlockPos samplePos = offsetAxis(offsetAxis(facePos, axisA, oa), axisB, ob);
                int color = engine.getColor(samplePos);
                sumR += ColorLightUtil.r(color);
                sumG += ColorLightUtil.g(color);
                sumB += ColorLightUtil.b(color);
            }
        }

        int avg = ColorLightUtil.pack(Math.round(sumR / 4f), Math.round(sumG / 4f), Math.round(sumB / 4f));

        return ColorLightUtil.max(avg, engine.getColor(pos));
    }

    private static float axisCoord(Direction.Axis axis, float x, float y, float z) {
        return switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    private static BlockPos offsetAxis(BlockPos pos, Direction.Axis axis, int amount) {
        if (amount == 0) return pos;
        return switch (axis) {
            case X -> pos.offset(amount, 0, 0);
            case Y -> pos.offset(0, amount, 0);
            case Z -> pos.offset(0, 0, amount);
        };
    }

    private SmoothLightSampler() {
    }
}