package com.hiveworkshop.model;

import java.util.List;
import java.util.Map;

/**
 * Full animation data extracted from an MDX/MDL model.
 *
 * <p>{@code geosetAlpha} maps geoset index → KGAO alpha AnimTrack (single float values).
 * {@code geosetStaticAlpha} maps geoset index → static fallback alpha (0–1).
 * {@code textureAnims} maps geoset index → texture animation tracks (KTAT/KTAR/KTAS).
 */
public record ModelAnimData(
        List<SequenceInfo>    sequences,
        BoneNode[]            bones,             // all nodes (bones + helpers), indexed by position
        List<GeosetSkinData>  geosets,           // one entry per geoset, same order as ModelMesh
        Map<Integer, AnimTrack> geosetAlpha,     // geoset index → KGAO track
        Map<Integer, Float>     geosetStaticAlpha, // geoset index → static alpha fallback
        Map<Integer, AnimTrack> geosetColor,     // geoset index → KGAC color track (vec3 RGB)
        Map<Integer, float[]>   geosetStaticColor, // geoset index → static color fallback [R,G,B]
        Map<Long, TextureAnimTracks> textureAnims, // (geosetIdx << 16 | layerIdx) → texture anim tracks
        Map<Long, AnimTrack>  layerAlpha,        // (geosetIdx << 16 | layerIdx) → KMTA track
        long[] globalSequences                   // global sequence durations (ms)
) {
    /** Encode geoset+layer index into a single map key. */
    public static long layerKey(int geosetIdx, int layerIdx) {
        return ((long) geosetIdx << 16) | (layerIdx & 0xFFFF);
    }

    public static final ModelAnimData EMPTY =
            new ModelAnimData(List.of(), new BoneNode[0], List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), new long[0]);

    /** Convenience constructor without geoset alpha/color (backward compat). */
    public ModelAnimData(List<SequenceInfo> sequences, BoneNode[] bones, List<GeosetSkinData> geosets) {
        this(sequences, bones, geosets, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), new long[0]);
    }

    public boolean hasAnimation() {
        return !sequences.isEmpty() && bones.length > 0;
    }
}
