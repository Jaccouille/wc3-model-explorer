package com.hiveworkshop.model;

public record ModelMesh(
        float[] vertices,
        float[] normals,
        int[] indices,
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ
) {
    public static final ModelMesh EMPTY = new ModelMesh(
            new float[0],
            new float[0],
            new int[0],
            0, 0, 0,
            0, 0, 0
    );

    public boolean isEmpty() {
        return vertices.length == 0 || indices.length == 0;
    }

    public float centerX() {
        return (minX + maxX) * 0.5f;
    }

    public float centerY() {
        return (minY + maxY) * 0.5f;
    }

    public float centerZ() {
        return (minZ + maxZ) * 0.5f;
    }

    public float radius() {
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f;
    }
}
