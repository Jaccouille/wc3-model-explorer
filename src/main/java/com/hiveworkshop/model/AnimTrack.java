package com.hiveworkshop.model;

/** Sampled keyframe track (translation, rotation, or scale). */
public record AnimTrack(
        long[]    frames,
        float[][] values,    // parallel to frames: [n][3] for vec3, [n][4] for quat
        float[][] inTans,
        float[][] outTans,
        int       interp,    // 0=DONT_INTERP, 1=LINEAR, 2=HERMITE, 3=BEZIER
        int       globalSequenceId  // index into globalSequences, -1 = none
) {
    public static final AnimTrack EMPTY =
            new AnimTrack(new long[0], new float[0][], new float[0][], new float[0][], 1, -1);

    /** Convenience constructor without globalSequenceId (defaults to -1). */
    public AnimTrack(long[] frames, float[][] values, float[][] inTans, float[][] outTans, int interp) {
        this(frames, values, inTans, outTans, interp, -1);
    }

    public boolean isEmpty() {
        return frames == null || frames.length == 0;
    }

    public boolean isGlobal() {
        return globalSequenceId >= 0;
    }
}
