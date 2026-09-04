package me.mrhikmen.colorlight.core.light.gpu;

public interface ILightComputeBackend {

    boolean isSupported();

    LightComputeResult propagateAndSmooth(int sizeX, int sizeY, int sizeZ, byte[] opacity, int[] baseColor, int iterations, float decayPerOpacityUnit);

    Object beginCompute(int sizeX, int sizeY, int sizeZ, byte[] opacity, int[] baseColor, int iterations, float decayPerOpacityUnit);

    LightComputeResult pollResult(Object token);

    void dispose();

    record LightComputeResult(int[] propagated, int[] smoothed) {
    }
}
