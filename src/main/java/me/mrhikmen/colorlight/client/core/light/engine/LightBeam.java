package me.mrhikmen.colorlight.client.core.light.engine;

/**
 * A directional dynamic light: it leaves its source point along a <b>view direction</b> (a camera or a flashlight)
 * and fills only a cone, instead of spreading in every direction like a point light.
 * <p>
 * Pass it to {@link ColorLightEngine.DynamicLightWorker#update(int, double, double, double, int, int, int, int, LightBeam)}.
 * Passing {@code null} there keeps the usual omnidirectional point light.
 * <p>
 * The shape and brightness follow the flashlight of the Illuminated mod: with {@code t} the distance along the view,
 * the cone's radius is {@code radius * (t + offset) / depth}, it reaches from {@code t = -offset} to
 * {@code t = depth - offset}, and its light level is {@code 15 * depth / t^1.5} (at most 15), faded over the last
 * block at the edge. Walls and opaque blocks cut the beam. The strength scales the whole beam (15 = as is).
 *
 * @param dirX   view direction, x component (normalised by the constructor)
 * @param dirY   view direction, y component
 * @param dirZ   view direction, z component
 * @param radius cone radius at its far end, in blocks
 * @param depth  cone length, in blocks
 * @param offset how far behind the source the cone's tip sits, in blocks
 */
public record LightBeam(double dirX, double dirY, double dirZ, double radius, double depth, double offset) {

    public LightBeam {
        double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (!(len > 1.0E-9))
            throw new IllegalArgumentException("direction must not be zero");
        if (!(radius > 0.0) || !(depth > 0.0) || !(offset >= 0.0 && offset < depth))
            throw new IllegalArgumentException("radius and depth must be positive, offset within [0, depth)");

        dirX /= len;
        dirY /= len;
        dirZ /= len;
    }

    /**
     * A beam along the view of an entity or camera, given the same yaw and pitch (in degrees) that Minecraft uses for
     * {@code getYRot()} / {@code getXRot()}.
     */
    public static LightBeam fromView(float yawDegrees, float pitchDegrees, double radius, double depth, double offset) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosPitch = Math.cos(pitch);
        return new LightBeam(
                -Math.sin(yaw) * cosPitch,
                -Math.sin(pitch),
                Math.cos(yaw) * cosPitch,
                radius, depth, offset
        );
    }
}
