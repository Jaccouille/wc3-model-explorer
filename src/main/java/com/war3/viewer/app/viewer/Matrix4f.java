package com.war3.viewer.app.viewer;

/**
 * Minimal column-major 4x4 float matrix for WC3 bone skinning.
 * Layout: index = col*4 + row  (same as OpenGL).
 */
public final class Matrix4f {
    public final float[] m = new float[16];

    private Matrix4f() {}

    public static Matrix4f identity() {
        final Matrix4f r = new Matrix4f();
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1f;
        return r;
    }

    public static Matrix4f translation(final float tx, final float ty, final float tz) {
        final Matrix4f r = identity();
        r.m[12] = tx;
        r.m[13] = ty;
        r.m[14] = tz;
        return r;
    }

    public static Matrix4f scale(final float sx, final float sy, final float sz) {
        final Matrix4f r = new Matrix4f();
        r.m[0] = sx;
        r.m[5] = sy;
        r.m[10] = sz;
        r.m[15] = 1f;
        return r;
    }

    /** Build rotation matrix from a quaternion (x, y, z, w). */
    public static Matrix4f fromQuaternion(final float qx, final float qy, final float qz, final float qw) {
        final Matrix4f r = new Matrix4f();
        final float x2 = qx * qx, y2 = qy * qy, z2 = qz * qz;
        final float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        final float wx = qw * qx, wy = qw * qy, wz = qw * qz;

        // Column 0
        r.m[0] = 1 - 2 * (y2 + z2);
        r.m[1] = 2 * (xy + wz);
        r.m[2] = 2 * (xz - wy);
        r.m[3] = 0f;
        // Column 1
        r.m[4] = 2 * (xy - wz);
        r.m[5] = 1 - 2 * (x2 + z2);
        r.m[6] = 2 * (yz + wx);
        r.m[7] = 0f;
        // Column 2
        r.m[8] = 2 * (xz + wy);
        r.m[9] = 2 * (yz - wx);
        r.m[10] = 1 - 2 * (x2 + y2);
        r.m[11] = 0f;
        // Column 3
        r.m[12] = r.m[13] = r.m[14] = 0f;
        r.m[15] = 1f;
        return r;
    }

    /** C = A * B (column-major, right-multiplication). */
    public static Matrix4f multiply(final Matrix4f a, final Matrix4f b) {
        final Matrix4f c = new Matrix4f();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a.m[k * 4 + row] * b.m[col * 4 + k];
                }
                c.m[col * 4 + row] = sum;
            }
        }
        return c;
    }

    /**
     * Transform a 3D point (affine multiply, w=1).
     * Returns new float[3].
     */
    public float[] transformPoint(final float x, final float y, final float z) {
        return new float[]{
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14]
        };
    }

    /**
     * Build the local bone matrix used in WC3 MDX skinning:
     *   M = translate(translation + pivot) * rotate(quat) * scale(sc) * translate(-pivot)
     */
    public static Matrix4f boneLocal(
            final float[] translation, final float[] quat, final float[] sc, final float[] pivot) {
        final float px = pivot[0], py = pivot[1], pz = pivot[2];
        final Matrix4f T = translation(translation[0] + px, translation[1] + py, translation[2] + pz);
        final Matrix4f R = fromQuaternion(quat[0], quat[1], quat[2], quat[3]);
        final Matrix4f S = scale(sc[0], sc[1], sc[2]);
        final Matrix4f negPivot = translation(-px, -py, -pz);
        return multiply(multiply(multiply(T, R), S), negPivot);
    }
}
