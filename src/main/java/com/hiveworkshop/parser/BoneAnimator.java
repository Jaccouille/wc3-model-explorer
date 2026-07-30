package com.hiveworkshop.parser;

import com.hiveworkshop.model.AnimTrack;
import com.hiveworkshop.model.BoneNode;
import com.hiveworkshop.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes world-space 4×4 matrices (column-major) for all bones at a given
 * animation time.
 *
 * Formula (from PLANS.md reference):
 *   boneLocal = T(pivot + trans) × R(quat) × S(scale) × T(−pivot)
 *   boneWorld = parentWorld × boneLocal
 */
public final class BoneAnimator {
    private static final float[] FULL_BILLBOARD_AXIS_CORRECTION =
            quatFromAxisAngle(0, 1, 0, (float) (Math.PI * 0.5));

    private BoneAnimator() {}

    /**
     * Returns a map from objectId → 4×4 column-major world matrix for every
     * bone in the supplied array.
     *
     * Billboard nodes orient to face the camera. The {@code cameraRotation}
     * quaternion [x,y,z,w] represents the camera orientation in model space
     * (Z-up WC3 coordinates). If null, billboards fall back to cancelling
     * parent rotation only.
     *
     * @param bones           all skeleton nodes (bones + helpers)
     * @param timeMs          current absolute animation time in milliseconds
     * @param seqStart        sequence interval start (ms)
     * @param seqEnd          sequence interval end   (ms)
     */
    public static Map<Integer, float[]> computeWorldMatrices(
            BoneNode[] bones, long timeMs, long seqStart, long seqEnd) {
        return computeWorldMatrices(bones, timeMs, seqStart, seqEnd, null, System.currentTimeMillis(), null);
    }

    public static Map<Integer, float[]> computeWorldMatrices(
            BoneNode[] bones, long timeMs, long seqStart, long seqEnd, long[] globalSequences) {
        return computeWorldMatrices(bones, timeMs, seqStart, seqEnd, globalSequences, System.currentTimeMillis(), null);
    }

    public static Map<Integer, float[]> computeWorldMatrices(
            BoneNode[] bones, long timeMs, long seqStart, long seqEnd,
            long[] globalSequences, long globalTimeMs) {
        return computeWorldMatrices(bones, timeMs, seqStart, seqEnd, globalSequences, globalTimeMs, null);
    }

    /** Convenience: uses System.currentTimeMillis() for global sequences. */
    public static Map<Integer, float[]> computeWorldMatrices(
            BoneNode[] bones, long timeMs, long seqStart, long seqEnd,
            long[] globalSequences, float[] cameraRotation) {
        return computeWorldMatrices(bones, timeMs, seqStart, seqEnd, globalSequences, System.currentTimeMillis(), cameraRotation);
    }

    /**
     * @param globalTimeMs   pausable wall-clock time for global sequences
     * @param cameraRotation camera orientation quaternion [x,y,z,w] in model space (Z-up),
     *                       or null to disable camera-facing billboards
     */
    public static Map<Integer, float[]> computeWorldMatrices(
            BoneNode[] bones, long timeMs, long seqStart, long seqEnd,
            long[] globalSequences, long globalTimeMs, float[] cameraRotation) {

        Map<Integer, float[]> worldMatrices = new HashMap<>(bones.length * 2);
        Map<Integer, float[]> worldRotations = new HashMap<>(bones.length * 2);
        Map<Integer, float[]> worldScales = new HashMap<>(bones.length * 2);
        Map<Integer, BoneNode> byId = new HashMap<>(bones.length * 2);
        for (BoneNode b : bones) byId.put(b.objectId(), b);

        for (BoneNode bone : bones) {
            computeWorldMatrix(bone, byId, worldMatrices, worldRotations, worldScales,
                    timeMs, seqStart, seqEnd, globalSequences, globalTimeMs, cameraRotation);
        }
        return worldMatrices;
    }

    // ── Recursive (memoised) world matrix computation ────────────────────────

    private static float[] computeWorldMatrix(
            BoneNode bone,
            Map<Integer, BoneNode> byId,
            Map<Integer, float[]> matCache,
            Map<Integer, float[]> rotCache,
            Map<Integer, float[]> scaleCache,
            long t, long s0, long s1, long[] globalSequences, long globalTimeMs,
            float[] cameraRotation) {

        if (matCache.containsKey(bone.objectId())) {
            return matCache.get(bone.objectId());
        }

        // Get parent world matrix, rotation, and scale
        float[] parentWorldMatrix = IDENTITY;
        float[] parentWorldRot = IDENTITY_QUAT;
        float[] parentWorldScale = ONE_VEC3;
        if (bone.parentId() >= 0) {
            BoneNode parent = byId.get(bone.parentId());
            if (parent != null) {
                computeWorldMatrix(parent, byId, matCache, rotCache, scaleCache,
                        t, s0, s1, globalSequences, globalTimeMs, cameraRotation);
                parentWorldMatrix = matCache.getOrDefault(parent.objectId(), IDENTITY);
                parentWorldRot = rotCache.getOrDefault(parent.objectId(), IDENTITY_QUAT);
                parentWorldScale = scaleCache.getOrDefault(parent.objectId(), ONE_VEC3);
            }
        }

        // Interpolate local transform components
        float[] p = bone.pivot();
        float px = p != null && p.length > 0 ? p[0] : 0f;
        float py = p != null && p.length > 1 ? p[1] : 0f;
        float pz = p != null && p.length > 2 ? p[2] : 0f;

        float[] tr = interpTrackVec3(bone.trans(), t, s0, s1, globalSequences, globalTimeMs, 0f, 0f, 0f);
        float[] localRot = interpTrackQuat(bone.rot(), t, s0, s1, globalSequences, globalTimeMs);
        float[] sc = interpTrackVec3(bone.scale(), t, s0, s1, globalSequences, globalTimeMs, 1f, 1f, 1f);

        // Inheritance cancellation (Retera convention):
        // Instead of decomposing the parent matrix, we always multiply
        // parentWorldMatrix × localMatrix, but modify localRot/localScale
        // to cancel the parent's contribution when DontInherit flags are set.
        float[] computedRot;
        float[] computedScale;
        int flags = bone.flags();

        // --- Rotation ---
        if (bone.isBillboarded() && cameraRotation != null) {
            computedRot = computeBillboardRotation(flags, cameraRotation, parentWorldRot, localRot);
        } else if (bone.isBillboarded()) {
            computedRot = quatMul(quatInverse(parentWorldRot), localRot);
        } else if (!bone.inheritsRotation()) {
            // DontInheritRotation: cancel parent rotation so local rot is in world space
            computedRot = quatMul(quatInverse(parentWorldRot), localRot);
        } else {
            computedRot = localRot;
        }

        // --- Scale ---
        if (!bone.inheritsScaling()) {
            // DontInheritScaling: cancel parent scale so local scale is absolute
            computedScale = new float[]{
                    parentWorldScale[0] != 0f ? sc[0] / parentWorldScale[0] : sc[0],
                    parentWorldScale[1] != 0f ? sc[1] / parentWorldScale[1] : sc[1],
                    parentWorldScale[2] != 0f ? sc[2] / parentWorldScale[2] : sc[2]
            };
        } else {
            computedScale = sc;
        }

        // boneLocal = T(pivot+trans) × R(computedRot) × S(computedScale) × T(-pivot)
        float[] localMatrix = makeTranslate(-px, -py, -pz);
        localMatrix = matMul(makeScale(computedScale[0], computedScale[1], computedScale[2]), localMatrix);
        localMatrix = matMul(quatToMatrix(computedRot[0], computedRot[1], computedRot[2], computedRot[3]), localMatrix);
        localMatrix = matMul(makeTranslate(tr[0] + px, tr[1] + py, tr[2] + pz), localMatrix);

        // worldMatrix = parentWorldMatrix × localMatrix (always — no decomposition)
        float[] worldMatrix = matMul(parentWorldMatrix, localMatrix);
        matCache.put(bone.objectId(), worldMatrix);

        // Track world rotation for children
        float[] worldRot;
        if (bone.isBillboarded() || !bone.inheritsRotation()) {
            // Billboard / DontInheritRotation: world rotation is just local rotation
            worldRot = localRot;
        } else {
            worldRot = quatMul(parentWorldRot, localRot);
        }
        rotCache.put(bone.objectId(), worldRot);

        // Track world scale for children
        float[] worldScale;
        if (!bone.inheritsScaling()) {
            worldScale = sc; // DontInheritScaling: world scale is just local scale
        } else {
            worldScale = mulVec3(parentWorldScale, sc);
        }
        scaleCache.put(bone.objectId(), worldScale);

        return worldMatrix;
    }

    /**
     * Computes the local rotation for a billboard bone that makes it face the camera.
     *
     * For full billboard (0x8): the bone faces the camera completely.
     * For lock-axis billboards: only rotates around the locked axis to face the camera.
     *
     * The result is a LOCAL rotation (to be composed with parent in the local matrix).
     * We want: parentWorldRot * result = cameraRot
     * So: result = inverse(parentWorldRot) * cameraRot
     */
    private static float[] computeBillboardRotation(int flags, float[] cameraRot,
                                                     float[] parentWorldRot, float[] localRot) {
        float[] invParent = quatInverse(parentWorldRot);
        float[] billboardBase;

        if ((flags & BoneNode.FLAG_BILLBOARDED) != 0) {
            // Full billboard: face camera completely
            billboardBase = quatMul(invParent, cameraRot);
            return applyBillboardLocalRotation(applyFullBillboardAxisCorrection(billboardBase), localRot);
        }

        // Lock-axis billboards: extract camera direction in parent-local space,
        // then rotate only around the locked axis to face it.
        // Get the camera forward direction in world space (camera looks along -Z in its local frame)
        float[] camFwd = quatRotateVec3(cameraRot, 0, 0, -1);
        // Transform to parent-local space
        float[] localFwd = quatRotateVec3(invParent, camFwd[0], camFwd[1], camFwd[2]);

        if ((flags & BoneNode.FLAG_BILLBOARDED_LOCK_Z) != 0) {
            // Rotate around Z axis (WC3 up axis) to face camera
            float angle = (float) Math.atan2(localFwd[1], localFwd[0]);
            billboardBase = quatFromAxisAngle(0, 0, 1, angle + (float)(Math.PI * 0.5));
            return applyBillboardLocalRotation(billboardBase, localRot);
        } else if ((flags & BoneNode.FLAG_BILLBOARDED_LOCK_Y) != 0) {
            // Rotate around Y axis
            float angle = (float) Math.atan2(localFwd[0], localFwd[2]);
            billboardBase = quatFromAxisAngle(0, 1, 0, angle);
            return applyBillboardLocalRotation(billboardBase, localRot);
        } else if ((flags & BoneNode.FLAG_BILLBOARDED_LOCK_X) != 0) {
            // Rotate around X axis
            float angle = (float) Math.atan2(localFwd[2], localFwd[1]);
            billboardBase = quatFromAxisAngle(1, 0, 0, angle);
            return applyBillboardLocalRotation(billboardBase, localRot);
        }

        // Fallback
        billboardBase = quatMul(invParent, cameraRot);
        return applyBillboardLocalRotation(billboardBase, localRot);
    }

    private static float[] applyBillboardLocalRotation(float[] billboardBase, float[] localRot) {
        return quatMul(billboardBase, localRot);
    }

    private static float[] applyFullBillboardAxisCorrection(float[] billboardBase) {
        return quatMul(billboardBase, FULL_BILLBOARD_AXIS_CORRECTION);
    }

    private static float[] mulVec3(float[] a, float[] b) {
        return new float[]{a[0] * b[0], a[1] * b[1], a[2] * b[2]};
    }

    /** Rotate a vector by a quaternion: q * v * q^-1. */
    private static float[] quatRotateVec3(float[] q, float vx, float vy, float vz) {
        float[] vq = {vx, vy, vz, 0};
        float[] result = quatMul(quatMul(q, vq), quatInverse(q));
        return new float[]{result[0], result[1], result[2]};
    }

    /** Create a quaternion from axis-angle (axis must be unit length). */
    private static float[] quatFromAxisAngle(float ax, float ay, float az, float angle) {
        float ha = angle * 0.5f;
        float s = (float) Math.sin(ha);
        return new float[]{ax * s, ay * s, az * s, (float) Math.cos(ha)};
    }

    /** Hamilton product of two quaternions [x,y,z,w]. */
    private static float[] quatMul(float[] a, float[] b) {
        return new float[]{
            a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
            a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
            a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
            a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
        };
    }

    /** Returns the inverse (conjugate) of a unit quaternion [x,y,z,w]. */
    private static float[] quatInverse(float[] q) {
        return new float[]{-q[0], -q[1], -q[2], q[3]};
    }

    // ── Global-sequence-aware dispatchers ───────────────────────────────────

    /**
     * Interpolate a Vec3 track, automatically using cyclic interpolation if the
     * track has a globalSequenceId, otherwise standard sequence-range interpolation.
     *
     * @param globalTimeMs  wall-clock-like time for global sequences (pausable by caller)
     */
    public static float[] interpTrackVec3(AnimTrack tk, long t, long s0, long s1,
                                          long[] globalSequences, long globalTimeMs,
                                          float defX, float defY, float defZ) {
        if (tk == null || tk.isEmpty()) return new float[]{defX, defY, defZ};
        int gsId = tk.globalSequenceId();
        if (gsId >= 0 && globalSequences != null && gsId < globalSequences.length && globalSequences[gsId] > 0) {
            return interpVec3Cyclic(tk, globalTimeMs, globalSequences[gsId], defX, defY, defZ);
        }
        return interpVec3(tk, t, s0, s1, defX, defY, defZ);
    }

    /** @deprecated Use the overload with globalTimeMs parameter */
    @Deprecated
    public static float[] interpTrackVec3(AnimTrack tk, long t, long s0, long s1,
                                          long[] globalSequences, float defX, float defY, float defZ) {
        return interpTrackVec3(tk, t, s0, s1, globalSequences, System.currentTimeMillis(), defX, defY, defZ);
    }

    /**
     * Interpolate a Quat track, automatically using cyclic interpolation if the
     * track has a globalSequenceId.
     *
     * @param globalTimeMs  wall-clock-like time for global sequences (pausable by caller)
     */
    public static float[] interpTrackQuat(AnimTrack tk, long t, long s0, long s1,
                                          long[] globalSequences, long globalTimeMs) {
        if (tk == null || tk.isEmpty()) return new float[]{0f, 0f, 0f, 1f};
        int gsId = tk.globalSequenceId();
        if (gsId >= 0 && globalSequences != null && gsId < globalSequences.length && globalSequences[gsId] > 0) {
            return interpQuatCyclic(tk, globalTimeMs, globalSequences[gsId]);
        }
        return interpQuat(tk, t, s0, s1);
    }

    /** @deprecated Use the overload with globalTimeMs parameter */
    @Deprecated
    public static float[] interpTrackQuat(AnimTrack tk, long t, long s0, long s1,
                                          long[] globalSequences) {
        return interpTrackQuat(tk, t, s0, s1, globalSequences, System.currentTimeMillis());
    }

    /**
     * Interpolate a scalar track, automatically using cyclic interpolation if the
     * track has a globalSequenceId.
     *
     * @param globalTimeMs  wall-clock-like time for global sequences (pausable by caller)
     */
    public static float interpTrackScalar(AnimTrack tk, long t, long s0, long s1,
                                          long[] globalSequences, long globalTimeMs, float def) {
        if (tk == null || tk.isEmpty()) return def;
        int gsId = tk.globalSequenceId();
        if (gsId >= 0 && globalSequences != null && gsId < globalSequences.length && globalSequences[gsId] > 0) {
            return interpScalarCyclic(tk, globalTimeMs, globalSequences[gsId], def);
        }
        return interpScalar(tk, t, s0, s1, def);
    }

    /** @deprecated Use the overload with globalTimeMs parameter */
    @Deprecated
    public static float interpTrackScalar(AnimTrack tk, long t, long s0, long s1,
                                          long[] globalSequences, float def) {
        return interpTrackScalar(tk, t, s0, s1, globalSequences, System.currentTimeMillis(), def);
    }

    // ── Track interpolation ──────────────────────────────────────────────────

    /**
     * Sample a scalar track (e.g. KGAO alpha) at time t.
     * Falls back to {@code def} when the track is empty.
     */
    public static float interpScalar(AnimTrack tk, long t, long s0, long s1, float def) {
        if (tk == null || tk.isEmpty()) return def;

        long[] frames = tk.frames();
        int n = frames.length;
        if (n == 0) return def;

        // Only consider keyframes within the sequence range [s0, s1]
        int firstInSeq = -1, lastInSeq = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] >= s0 && frames[i] <= s1) {
                if (firstInSeq < 0) firstInSeq = i;
                lastInSeq = i;
            }
        }
        if (firstInSeq < 0) return def; // no keys in this sequence

        // Bracket search restricted to [s0, s1]
        int lo = -1, hi = -1;
        for (int i = firstInSeq; i <= lastInSeq; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] >= t && hi < 0) hi = i;
        }

        if (lo < 0) return scalar(tk.values()[firstInSeq]);
        if (hi < 0) {
            // Past last in-sequence keyframe — wrap to first keyframe at s1
            if (firstInSeq != lastInSeq && s1 > frames[lastInSeq]) {
                long t0 = frames[lastInSeq], t1 = s1;
                float alpha = Math.min((float)(t - t0) / (float)(t1 - t0), 1f);
                return lerpScalar(scalar(tk.values()[lastInSeq]), scalar(tk.values()[firstInSeq]), alpha);
            }
            return scalar(tk.values()[lo]);
        }
        if (lo == hi) return scalar(tk.values()[lo]);

        long t0 = frames[lo], t1 = frames[hi];
        if (t1 == t0) return scalar(tk.values()[lo]);

        float alpha = (float)(t - t0) / (float)(t1 - t0);
        float v0 = scalar(tk.values()[lo]);
        float v1 = scalar(tk.values()[hi]);

        return switch (tk.interp()) {
            case 0 -> v0; // DONT_INTERP
            default -> lerpScalar(v0, v1, alpha);
        };
    }

    private static float scalar(float[] v) {
        return (v != null && v.length > 0) ? v[0] : 0f;
    }

    private static float lerpScalar(float a, float b, float t) {
        return a + t * (b - a);
    }

    /**
     * Sample a Vec3 track at time t, returning [dx,dy,dz].
     * Falls back to (defX, defY, defZ) when the track is empty.
     */
    public static float[] interpVec3(AnimTrack tk, long t, long s0, long s1,
                                      float defX, float defY, float defZ) {
        if (tk == null || tk.isEmpty()) return new float[]{defX, defY, defZ};

        long[] frames = tk.frames();
        int n = frames.length;
        if (n == 0) return new float[]{defX, defY, defZ};

        // Only consider keyframes within the sequence range [s0, s1]
        int firstInSeq = -1, lastInSeq = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] >= s0 && frames[i] <= s1) {
                if (firstInSeq < 0) firstInSeq = i;
                lastInSeq = i;
            }
        }
        if (firstInSeq < 0) return new float[]{defX, defY, defZ}; // no keys in this sequence

        // Bracket search restricted to [s0, s1]
        int lo = -1, hi = -1;
        for (int i = firstInSeq; i <= lastInSeq; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] >= t && hi < 0) hi = i;
        }

        if (lo < 0) return copy3(tk.values()[firstInSeq]);
        if (hi < 0) {
            // Past last in-sequence keyframe — wrap to first keyframe at s1
            if (firstInSeq != lastInSeq && s1 > frames[lastInSeq]) {
                long t0 = frames[lastInSeq], t1 = s1;
                float alpha = Math.min((float)(t - t0) / (float)(t1 - t0), 1f);
                return lerpVec3(tk.values()[lastInSeq], tk.values()[firstInSeq], alpha);
            }
            return copy3(tk.values()[lo]);
        }
        if (lo == hi) return copy3(tk.values()[lo]); // exact hit

        long t0 = frames[lo], t1 = frames[hi];
        if (t1 == t0) return copy3(tk.values()[lo]);

        float alpha = (float)(t - t0) / (float)(t1 - t0);
        float[] v0 = tk.values()[lo];
        float[] v1 = tk.values()[hi];

        return switch (tk.interp()) {
            case 0 -> copy3(v0);            // DONT_INTERP
            case 2 -> hermiteVec3(v0, tk.outTans()[lo], tk.inTans()[hi], v1, alpha);
            case 3 -> bezierVec3(v0, tk.outTans()[lo], tk.inTans()[hi], v1, alpha);
            default -> lerpVec3(v0, v1, alpha);  // LINEAR (1)
        };
    }

    /**
     * Sample a quaternion track at time t, returning [x,y,z,w].
     * Uses SLERP for all interpolation types (good enough for display).
     */
    public static float[] interpQuat(AnimTrack tk, long t, long s0, long s1) {
        if (tk == null || tk.isEmpty()) return new float[]{0f, 0f, 0f, 1f};

        long[] frames = tk.frames();
        int n = frames.length;
        if (n == 0) return new float[]{0f, 0f, 0f, 1f};

        // Only consider keyframes within the sequence range [s0, s1]
        int firstInSeq = -1, lastInSeq = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] >= s0 && frames[i] <= s1) {
                if (firstInSeq < 0) firstInSeq = i;
                lastInSeq = i;
            }
        }
        if (firstInSeq < 0) return new float[]{0f, 0f, 0f, 1f}; // no keys in this sequence

        // Bracket search restricted to [s0, s1]
        int lo = -1, hi = -1;
        for (int i = firstInSeq; i <= lastInSeq; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] >= t && hi < 0) hi = i;
        }

        if (lo < 0) return copy4(tk.values()[firstInSeq]);
        if (hi < 0) {
            // Past last in-sequence keyframe — wrap to first keyframe at s1
            if (firstInSeq != lastInSeq && s1 > frames[lastInSeq] && tk.interp() != 0) {
                long t0 = frames[lastInSeq], t1 = s1;
                float alpha = Math.min((float)(t - t0) / (float)(t1 - t0), 1f);
                return slerp(tk.values()[lastInSeq], tk.values()[firstInSeq], alpha);
            }
            return copy4(tk.values()[lo]);
        }
        if (lo == hi) return copy4(tk.values()[lo]);

        long t0 = frames[lo], t1 = frames[hi];
        if (t1 == t0) return copy4(tk.values()[lo]);

        if (tk.interp() == 0) return copy4(tk.values()[lo]); // DONT_INTERP

        float alpha = (float)(t - t0) / (float)(t1 - t0);
        return slerp(tk.values()[lo], tk.values()[hi], alpha);
    }

    // ── Vec3 interpolation methods ───────────────────────────────────────────

    private static float[] lerpVec3(float[] a, float[] b, float t) {
        return new float[]{
            a[0] + t * (b[0] - a[0]),
            a[1] + t * (b[1] - a[1]),
            a[2] + t * (b[2] - a[2])
        };
    }

    private static float[] hermiteVec3(float[] p0, float[] out0, float[] in1, float[] p1, float t) {
        float t2 = t * t, t3 = t2 * t;
        float h0 = 2*t3 - 3*t2 + 1;
        float h1 = t3 - 2*t2 + t;
        float h2 = -2*t3 + 3*t2;
        float h3 = t3 - t2;
        return new float[]{
            h0*p0[0] + h1*out0[0] + h2*p1[0] + h3*in1[0],
            h0*p0[1] + h1*out0[1] + h2*p1[1] + h3*in1[1],
            h0*p0[2] + h1*out0[2] + h2*p1[2] + h3*in1[2]
        };
    }

    private static float[] bezierVec3(float[] p0, float[] c0, float[] c1, float[] p1, float t) {
        float mt = 1f - t, mt2 = mt*mt, mt3 = mt2*mt, t2 = t*t, t3 = t2*t;
        return new float[]{
            mt3*p0[0] + 3*mt2*t*c0[0] + 3*mt*t2*c1[0] + t3*p1[0],
            mt3*p0[1] + 3*mt2*t*c0[1] + 3*mt*t2*c1[1] + t3*p1[1],
            mt3*p0[2] + 3*mt2*t*c0[2] + 3*mt*t2*c1[2] + t3*p1[2]
        };
    }

    // ── Quaternion SLERP ─────────────────────────────────────────────────────

    static float[] slerp(float[] q1, float[] q2, float t) {
        float x1 = q1[0], y1 = q1[1], z1 = q1[2], w1 = q1[3];
        float x2 = q2[0], y2 = q2[1], z2 = q2[2], w2 = q2[3];

        float dot = x1*x2 + y1*y2 + z1*z2 + w1*w2;
        if (dot < 0f) { x2 = -x2; y2 = -y2; z2 = -z2; w2 = -w2; dot = -dot; }

        if (dot > 0.9995f) {
            // Linear fallback for nearly-identical quaternions
            float[] r = { x1 + t*(x2-x1), y1 + t*(y2-y1), z1 + t*(z2-z1), w1 + t*(w2-w1) };
            return normalizeQuat(r);
        }

        float theta0 = (float) Math.acos(dot);
        float theta  = theta0 * t;
        float sinT0  = (float) Math.sin(theta0);
        float sinT   = (float) Math.sin(theta);

        float s1 = (float) Math.cos(theta) - dot * sinT / sinT0;
        float s2 = sinT / sinT0;

        return new float[]{
            s1*x1 + s2*x2,
            s1*y1 + s2*y2,
            s1*z1 + s2*z2,
            s1*w1 + s2*w2
        };
    }

    private static float[] normalizeQuat(float[] q) {
        float len = (float) Math.sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
        if (len < 1e-10f) return new float[]{0f, 0f, 0f, 1f};
        return new float[]{q[0]/len, q[1]/len, q[2]/len, q[3]/len};
    }

    // ── Matrix utilities (column-major 4×4) ──────────────────────────────────

    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private static final float[] IDENTITY_QUAT = {0f, 0f, 0f, 1f};
    private static final float[] ZERO_VEC3 = {0f, 0f, 0f};
    private static final float[] ONE_VEC3 = {1f, 1f, 1f};

    static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }

    private static float[] makeTranslate(float tx, float ty, float tz) {
        float[] m = identity();
        m[12] = tx; m[13] = ty; m[14] = tz;
        return m;
    }

    private static float[] makeScale(float sx, float sy, float sz) {
        float[] m = identity();
        m[0] = sx; m[5] = sy; m[10] = sz;
        return m;
    }

    /** Build a rotation matrix from quaternion (x, y, z, w). Column-major. */
    static float[] quatToMatrix(float x, float y, float z, float w) {
        float xx = x*x, yy = y*y, zz = z*z;
        float xy = x*y, xz = x*z, yz = y*z;
        float wx = w*x, wy = w*y, wz = w*z;
        return new float[]{
            1-2*(yy+zz), 2*(xy+wz),   2*(xz-wy),  0,   // col 0
            2*(xy-wz),   1-2*(xx+zz), 2*(yz+wx),   0,   // col 1
            2*(xz+wy),   2*(yz-wx),   1-2*(xx+yy), 0,   // col 2
            0,           0,           0,            1    // col 3
        };
    }

    /** Column-major 4×4 matrix multiply: returns a × b. */
    static float[] matMul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[row + k*4] * b[k + col*4];
                r[row + col*4] = s;
            }
        }
        return r;
    }

    // ── Cyclic interpolation for global sequences ──────────────────────────

    /**
     * Sample a Vec3 track cyclically for a global sequence with the given period.
     * Handles wrap-around: after the last keyframe, smoothly transitions back
     * to the first keyframe to complete the loop.
     */
    public static float[] interpVec3Cyclic(AnimTrack tk, long t, long period,
                                           float defX, float defY, float defZ) {
        if (tk == null || tk.isEmpty()) return new float[]{defX, defY, defZ};
        long[] frames = tk.frames();
        float[][] values = tk.values();
        int n = frames.length;
        if (n == 0) return new float[]{defX, defY, defZ};
        if (n == 1) return copy3(values[0]);

        // Wrap time to [0, period)
        t = ((t % period) + period) % period;

        // Find bracket keyframes
        int lo = -1, hi = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] > t && hi < 0) hi = i;
        }

        // Exact hit on a keyframe
        if (lo >= 0 && hi < 0 && frames[lo] == t) return copy3(values[lo]);
        if (lo >= 0 && hi >= 0 && frames[lo] == t) return copy3(values[lo]);

        // Normal bracket: both found
        if (lo >= 0 && hi >= 0) {
            long t0 = frames[lo], t1 = frames[hi];
            if (t1 == t0) return copy3(values[lo]);
            float alpha = (float)(t - t0) / (float)(t1 - t0);
            return interpVec3Switch(tk, lo, hi, alpha);
        }

        // Wrap case: t is past last keyframe, wrapping to first
        if (lo >= 0 && hi < 0) {
            long gap = (period - frames[lo]) + frames[0];
            if (gap <= 0) return copy3(values[lo]);
            float alpha = (float)(t - frames[lo]) / (float)gap;
            return lerpVec3(values[lo], values[0], alpha);
        }

        // t is before first keyframe, wrapping from last
        if (lo < 0 && hi >= 0) {
            int last = n - 1;
            long gap = (period - frames[last]) + frames[hi];
            if (gap <= 0) return copy3(values[hi]);
            float alpha = (float)((period - frames[last]) + t) / (float)gap;
            return lerpVec3(values[last], values[hi], alpha);
        }

        return new float[]{defX, defY, defZ};
    }

    /**
     * Sample a quaternion track cyclically for a global sequence with the given period.
     */
    public static float[] interpQuatCyclic(AnimTrack tk, long t, long period) {
        if (tk == null || tk.isEmpty()) return new float[]{0f, 0f, 0f, 1f};
        long[] frames = tk.frames();
        float[][] values = tk.values();
        int n = frames.length;
        if (n == 0) return new float[]{0f, 0f, 0f, 1f};
        if (n == 1) return copy4(values[0]);

        t = ((t % period) + period) % period;

        int lo = -1, hi = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] > t && hi < 0) hi = i;
        }

        if (lo >= 0 && frames[lo] == t) return copy4(values[lo]);

        if (lo >= 0 && hi >= 0) {
            long t0 = frames[lo], t1 = frames[hi];
            if (t1 == t0) return copy4(values[lo]);
            float alpha = (float)(t - t0) / (float)(t1 - t0);
            return (tk.interp() == 0) ? copy4(values[lo]) : slerp(values[lo], values[hi], alpha);
        }

        if (lo >= 0 && hi < 0) {
            long gap = (period - frames[lo]) + frames[0];
            if (gap <= 0) return copy4(values[lo]);
            float alpha = (float)(t - frames[lo]) / (float)gap;
            return slerp(values[lo], values[0], alpha);
        }

        if (lo < 0 && hi >= 0) {
            int last = n - 1;
            long gap = (period - frames[last]) + frames[hi];
            if (gap <= 0) return copy4(values[hi]);
            float alpha = (float)((period - frames[last]) + t) / (float)gap;
            return slerp(values[last], values[hi], alpha);
        }

        return new float[]{0f, 0f, 0f, 1f};
    }

    /**
     * Sample a scalar track cyclically for a global sequence with the given period.
     */
    public static float interpScalarCyclic(AnimTrack tk, long t, long period, float def) {
        if (tk == null || tk.isEmpty()) return def;
        long[] frames = tk.frames();
        float[][] values = tk.values();
        int n = frames.length;
        if (n == 0) return def;
        if (n == 1) return scalar(values[0]);

        t = ((t % period) + period) % period;

        int lo = -1, hi = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] > t && hi < 0) hi = i;
        }

        if (lo >= 0 && frames[lo] == t) return scalar(values[lo]);

        if (lo >= 0 && hi >= 0) {
            long t0 = frames[lo], t1 = frames[hi];
            if (t1 == t0) return scalar(values[lo]);
            float alpha = (float)(t - t0) / (float)(t1 - t0);
            return (tk.interp() == 0) ? scalar(values[lo]) : lerpScalar(scalar(values[lo]), scalar(values[hi]), alpha);
        }

        if (lo >= 0 && hi < 0) {
            long gap = (period - frames[lo]) + frames[0];
            if (gap <= 0) return scalar(values[lo]);
            float alpha = (float)(t - frames[lo]) / (float)gap;
            return lerpScalar(scalar(values[lo]), scalar(values[0]), alpha);
        }

        if (lo < 0 && hi >= 0) {
            int last = n - 1;
            long gap = (period - frames[last]) + frames[hi];
            if (gap <= 0) return scalar(values[hi]);
            float alpha = (float)((period - frames[last]) + t) / (float)gap;
            return lerpScalar(scalar(values[last]), scalar(values[hi]), alpha);
        }

        return def;
    }

    private static float[] interpVec3Switch(AnimTrack tk, int lo, int hi, float alpha) {
        float[] v0 = tk.values()[lo];
        float[] v1 = tk.values()[hi];
        return switch (tk.interp()) {
            case 0 -> copy3(v0);
            case 2 -> hermiteVec3(v0, tk.outTans()[lo], tk.inTans()[hi], v1, alpha);
            case 3 -> bezierVec3(v0, tk.outTans()[lo], tk.inTans()[hi], v1, alpha);
            default -> lerpVec3(v0, v1, alpha);
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static float[] copy3(float[] v) {
        if (v == null || v.length < 3) return new float[3];
        return new float[]{v[0], v[1], v[2]};
    }

    private static float[] copy4(float[] v) {
        if (v == null || v.length < 4) return new float[]{0f, 0f, 0f, 1f};
        return new float[]{v[0], v[1], v[2], v[3]};
    }
}
