package com.hiveworkshop.model;

/** One node (bone, helper, or attachment) in the WC3 skeleton hierarchy. */
public record BoneNode(
        int       objectId,
        int       parentId,   // -1 if root
        String    name,       // node name from the MDX/MDL file
        NodeType  nodeType,   // BONE, HELPER, or ATTACHMENT
        int       flags,      // raw MDX flags (billboard bits, inheritance bits, etc.)
        float[]   pivot,      // [x, y, z] pivot point in model space
        AnimTrack trans,      // KGTR – translation track
        AnimTrack rot,        // KGRT – rotation (quaternion xyzw) track
        AnimTrack scale       // KGSC – scale track
) {
    public enum NodeType { BONE, HELPER, ATTACHMENT, RIBBON_EMITTER, PARTICLE_EMITTER2 }

    // Billboard flag masks from MdlxGenericObject
    public static final int FLAG_DONT_INHERIT_TRANSLATION = 0x1;
    public static final int FLAG_DONT_INHERIT_SCALING     = 0x2;
    public static final int FLAG_DONT_INHERIT_ROTATION    = 0x4;
    public static final int FLAG_BILLBOARDED        = 0x8;
    public static final int FLAG_BILLBOARDED_LOCK_X = 0x10;
    public static final int FLAG_BILLBOARDED_LOCK_Y = 0x20;
    public static final int FLAG_BILLBOARDED_LOCK_Z = 0x40;

    /** True if any billboard flag is set. */
    public boolean isBillboarded() {
        return (flags & (FLAG_BILLBOARDED | FLAG_BILLBOARDED_LOCK_X
                       | FLAG_BILLBOARDED_LOCK_Y | FLAG_BILLBOARDED_LOCK_Z)) != 0;
    }

    public boolean inheritsTranslation() {
        return (flags & FLAG_DONT_INHERIT_TRANSLATION) == 0;
    }

    public boolean inheritsRotation() {
        return (flags & FLAG_DONT_INHERIT_ROTATION) == 0;
    }

    public boolean inheritsScaling() {
        return (flags & FLAG_DONT_INHERIT_SCALING) == 0;
    }

    /** Backwards-compatible constructor without name/type/flags. */
    public BoneNode(int objectId, int parentId, float[] pivot,
                    AnimTrack trans, AnimTrack rot, AnimTrack scale) {
        this(objectId, parentId, "", NodeType.BONE, 0, pivot, trans, rot, scale);
    }
}
