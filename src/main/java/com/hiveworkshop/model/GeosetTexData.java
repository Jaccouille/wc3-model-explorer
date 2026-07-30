package com.hiveworkshop.model;

import java.util.List;

/**
 * Per-geoset texture + UV information needed for textured rendering.
 *
 * <p>A WC3 material can have multiple layers. Each layer has its own texture,
 * filter mode, alpha, and flags. Layers are drawn in order on the same geometry.
 *
 * <p>The top-level {@code texturePath}, {@code filterMode}, and {@code replaceableId}
 * fields represent the "effective" (first/primary) layer for backward compatibility.
 *
 * @param uvSets        all UV coordinate sets for this geoset (indexed by coordId)
 * @param layers        ordered list of material layers to render
 * @param texturePath   primary texture path (from effective layer)
 * @param filterMode    primary filter mode ordinal
 * @param replaceableId 0=normal, 1=TeamColor, 2=TeamGlow
 */
public record GeosetTexData(
        float[][] uvSets,
        List<LayerTexData> layers,
        String texturePath,
        int filterMode,
        int replaceableId
) {
    public static final GeosetTexData EMPTY = new GeosetTexData(
            new float[0][], List.of(), "", 0, 0);

    /** Backward-compatible constructor (single UV set, no layers list). */
    public GeosetTexData(float[] uvs, String texturePath, int filterMode, int replaceableId) {
        this(new float[][]{uvs}, List.of(), texturePath, filterMode, replaceableId);
    }

    /** Primary UV set (coordId 0). */
    public float[] uvs() {
        return (uvSets != null && uvSets.length > 0) ? uvSets[0] : new float[0];
    }

    public boolean hasUvs() {
        float[] uv0 = uvs();
        return uv0 != null && uv0.length > 0;
    }

    /** True if this geoset has a team color layer (replaceableId 1 or 2). */
    public boolean hasTeamColor() {
        return replaceableId == 1 || replaceableId == 2;
    }

    /** True if ALL layers in this geoset are opaque for two-pass ordering. */
    public boolean isOpaque() {
        if (!layers.isEmpty()) {
            for (LayerTexData layer : layers) {
                if (!layer.isOpaque()) return false;
            }
            return true;
        }
        // Fallback for legacy single-layer path
        if (replaceableId == 1) return true;
        if (replaceableId == 2) return false;
        return filterMode <= 1;
    }

    /** True if this geoset has multiple material layers. */
    public boolean isMultiLayer() {
        return layers.size() > 1;
    }

    /**
     * Per-layer texture data within a material.
     */
    public record LayerTexData(
            String texturePath,
            int filterMode,
            int replaceableId,
            float alpha,
            int flags,
            int coordId
    ) {
        public boolean isOpaque() {
            if (replaceableId == 2) return false;
            return filterMode <= 1;
        }

        public boolean isTwoSided()    { return (flags & 0x10) != 0; }
        public boolean isUnshaded()    { return (flags & 0x01) != 0; }
        public boolean isNoDepthTest() { return (flags & 0x04) != 0; }
        public boolean isNoDepthSet()  { return (flags & 0x08) != 0; }
    }
}
