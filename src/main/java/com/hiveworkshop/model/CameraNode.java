package com.hiveworkshop.model;

/** Camera definition from an MDX/MDL model. */
public record CameraNode(
        String  name,
        float[] position,        // [x, y, z] camera position in model space
        float[] targetPosition,  // [x, y, z] look-at target in model space
        float   fieldOfView,     // FOV in radians
        float   nearClip,
        float   farClip
) {
    public static final CameraNode[] EMPTY_ARRAY = new CameraNode[0];
}
