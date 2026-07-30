package com.hiveworkshop.model;

/**
 * Parsed ParticleEmitter2 data from an MDX/MDL model.
 *
 * <p>PE2 is the primary particle system in Warcraft 3. Each emitter spawns billboard
 * quads (head) and/or velocity-oriented strips (tail) with a 3-segment lifecycle
 * (birth → middle → death) controlling color, alpha, and scale.
 */
public record ParticleEmitter2Data(
        int objectId,
        int textureId,
        int filterMode,        // 0=Blend, 1=Additive, 2=Modulate, 3=Modulate2x, 4=AlphaKey
        int flags,
        float speed,
        float variation,
        float latitude,
        float gravity,
        float lifeSpan,
        float emissionRate,
        float width,
        float length,
        float tailLength,
        float timeMiddle,
        int headOrTail,        // 0=Head, 1=Tail, 2=Both
        int rows,
        int columns,
        int squirt,
        int priorityPlane,
        int replaceableId,
        float[][] segmentColors,  // [3][3] RGB for birth/middle/death
        short[] segmentAlphas,    // [3] alpha 0-255 for birth/middle/death
        float[] segmentScaling,   // [3] scale for birth/middle/death
        long[][] headIntervals,   // [2][3] head UV frame ranges
        long[][] tailIntervals,   // [2][3] tail UV frame ranges
        String texturePath,
        // Animation tracks
        AnimTrack speedTrack,          // KP2S
        AnimTrack variationTrack,      // KP2R
        AnimTrack latitudeTrack,       // KP2L
        AnimTrack gravityTrack,        // KP2G
        AnimTrack emissionRateTrack,   // KP2E
        AnimTrack widthTrack,          // KP2W
        AnimTrack lengthTrack,         // KP2N
        AnimTrack visibilityTrack      // KP2V
) {
    public static final ParticleEmitter2Data[] EMPTY_ARRAY = new ParticleEmitter2Data[0];

    public boolean isModelSpace()  { return (flags & 0x80000)  != 0; }
    public boolean isLineEmitter() { return (flags & 0x20000)  != 0; }
    public boolean isUnshaded()    { return (flags & 0x8000)   != 0; }
    public boolean isXYQuad()      { return (flags & 0x100000) != 0; }
}
