package com.hiveworkshop.model;

/**
 * Per-geoset texture animation tracks (KTAT / KTAR / KTAS).
 *
 * <p>Translation and scale are Vec3 tracks; rotation is a quaternion (Vec4) track.
 * When all three are empty, no UV transformation is needed for this geoset.
 */
public record TextureAnimTracks(
        AnimTrack translation,  // KTAT — vec3
        AnimTrack rotation,     // KTAR — quat [x,y,z,w]
        AnimTrack scale         // KTAS — vec3
) {
    public static final TextureAnimTracks EMPTY =
            new TextureAnimTracks(AnimTrack.EMPTY, AnimTrack.EMPTY, AnimTrack.EMPTY);

    public boolean hasAnimation() {
        return !translation.isEmpty() || !rotation.isEmpty() || !scale.isEmpty();
    }

    /** Returns the globalSequenceId from the first non-empty track, or -1. */
    public int globalSequenceId() {
        if (!translation.isEmpty()) return translation.globalSequenceId();
        if (!rotation.isEmpty()) return rotation.globalSequenceId();
        if (!scale.isEmpty()) return scale.globalSequenceId();
        return -1;
    }
}
