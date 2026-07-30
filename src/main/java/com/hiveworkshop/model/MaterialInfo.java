package com.hiveworkshop.model;

import java.util.List;

/**
 * Describes a WC3 material and its layers, extracted from MDX/MDL.
 *
 * @param index         material index in the model
 * @param priorityPlane priority plane value
 * @param flags         material flags (e.g. constant color, sort primitives far-z)
 * @param shader        shader name (HD models), empty for classic
 * @param layers        ordered list of material layers
 */
public record MaterialInfo(
        int index,
        int priorityPlane,
        int flags,
        String shader,
        List<LayerInfo> layers
) {
    public static final MaterialInfo[] EMPTY_ARRAY = new MaterialInfo[0];

    /**
     * A single layer within a material.
     *
     * @param filterMode      blend mode ordinal (0=None,1=Transparent,2=Blend,3=Additive,4=AddAlpha,5=Modulate,6=Modulate2x)
     * @param flags           layer flags bitmask (unshaded, unfogged, two-sided, no-depth-test, etc.)
     * @param textureId       index into model's texture array
     * @param texturePath     resolved texture path (empty if none)
     * @param replaceableId   0=normal, 1=TeamColor, 2=TeamGlow
     * @param alpha           static alpha (0.0-1.0)
     * @param textureAnimId   texture animation id (-1 if none)
     * @param coordId         UV coordinate set id
     */
    public record LayerInfo(
            int filterMode,
            int flags,
            int textureId,
            String texturePath,
            int replaceableId,
            float alpha,
            int textureAnimId,
            long coordId
    ) {
        public String filterModeName() {
            return switch (filterMode) {
                case 0 -> "None";
                case 1 -> "Transparent";
                case 2 -> "Blend";
                case 3 -> "Additive";
                case 4 -> "AddAlpha";
                case 5 -> "Modulate";
                case 6 -> "Modulate2x";
                default -> "Unknown(" + filterMode + ")";
            };
        }

        public boolean isUnshaded()    { return (flags & 0x01) != 0; }
        public boolean isTwoSided()    { return (flags & 0x10) != 0; }
        public boolean isUnfogged()    { return (flags & 0x02) != 0; }
        public boolean isNoDepthTest() { return (flags & 0x04) != 0; }
        public boolean isNoDepthSet()  { return (flags & 0x08) != 0; }
    }
}
