package com.hiveworkshop.model;

/** Collision shape definition from an MDX/MDL model. */
public record CollisionShape(
        ShapeType type,
        float[][] vertices,    // 1 or 2 vertices (each [x,y,z])
        float     boundsRadius // used for SPHERE and CYLINDER
) {
    public enum ShapeType { BOX, PLANE, SPHERE, CYLINDER }

    public static final CollisionShape[] EMPTY_ARRAY = new CollisionShape[0];
}
