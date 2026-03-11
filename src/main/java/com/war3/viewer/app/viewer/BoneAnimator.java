package com.war3.viewer.app.viewer;

import com.hiveworkshop.rms.parsers.mdlx.AnimationMap;
import com.hiveworkshop.rms.parsers.mdlx.InterpolationType;
import com.hiveworkshop.rms.parsers.mdlx.MdlxGenericObject;
import com.hiveworkshop.rms.parsers.mdlx.MdlxGeoset;
import com.hiveworkshop.rms.parsers.mdlx.MdlxModel;
import com.hiveworkshop.rms.parsers.mdlx.MdlxSequence;
import com.hiveworkshop.rms.parsers.mdlx.timeline.MdlxFloatArrayTimeline;
import com.hiveworkshop.rms.parsers.mdlx.timeline.MdlxTimeline;
import com.hiveworkshop.rms.util.War3ID;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Computes animated vertex positions for MDX models using the bone hierarchy
 * and timeline keyframes (DONT_INTERP + LINEAR interpolation supported).
 */
public final class BoneAnimator {
    private static final float[] IDENTITY_TRANS = {0f, 0f, 0f};
    private static final float[] IDENTITY_ROT   = {0f, 0f, 0f, 1f};
    private static final float[] IDENTITY_SCALE  = {1f, 1f, 1f};

    private final MdlxModel model;
    /** All node objects sorted by objectId. */
    private final List<MdlxGenericObject> allNodes;
    /** Maps objectId → index in allNodes. */
    private final Map<Integer, Integer> nodeIndexById;
    /** parentIndices[i] = index of parent in allNodes, or -1 for root. */
    private final int[] parentIndices;
    /** Node indices in topological order (every parent appears before its children). */
    private final int[] topoOrder;

    public BoneAnimator(final MdlxModel model) {
        this.model = model;
        this.allNodes = buildNodeList(model);
        this.nodeIndexById = new HashMap<>();
        for (int i = 0; i < allNodes.size(); i++) {
            nodeIndexById.put(allNodes.get(i).objectId, i);
        }
        this.parentIndices = buildParentIndices();
        this.topoOrder = buildTopologicalOrder();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the animated vertex positions (in WC3 model space) for the given
     * geoset at the specified animation frame.
     *
     * @param geosetIndex index in model.geosets
     * @param frame       frame number within the sequence's interval
     * @return float[] of length vertexCount * 3 in WC3 (Y-up) space
     */
    public float[] computeAnimatedVertices(final int geosetIndex, final long frame,
                                            final long seqStart, final long seqEnd) {
        final MdlxGeoset geoset = model.geosets.get(geosetIndex);
        final int vertexCount = geoset.vertices.length / 3;
        final float[] result = new float[geoset.vertices.length];

        final Matrix4f[] worldTransforms = buildWorldTransforms(frame, seqStart, seqEnd);

        // Precompute prefix sums for matrixGroups to locate group offsets
        final long[] matrixGroupOffsets = new long[geoset.matrixGroups.length + 1];
        for (int g = 0; g < geoset.matrixGroups.length; g++) {
            matrixGroupOffsets[g + 1] = matrixGroupOffsets[g] + geoset.matrixGroups[g];
        }

        for (int i = 0; i < vertexCount; i++) {
            final float vx = geoset.vertices[i * 3];
            final float vy = geoset.vertices[i * 3 + 1];
            final float vz = geoset.vertices[i * 3 + 2];

            int groupIdx = 0;
            if (geoset.vertexGroups != null && i < geoset.vertexGroups.length) {
                groupIdx = geoset.vertexGroups[i] & 0xFF; // unsigned byte
            }

            // Collect bone objectIds for this vertex group
            final float[] animated = transformVertex(vx, vy, vz, groupIdx,
                    matrixGroupOffsets, geoset, worldTransforms);
            result[i * 3]     = animated[0];
            result[i * 3 + 1] = animated[1];
            result[i * 3 + 2] = animated[2];
        }
        return result;
    }

    public List<MdlxSequence> getSequences() {
        return model.sequences;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private float[] transformVertex(
            final float vx, final float vy, final float vz,
            final int groupIdx,
            final long[] matrixGroupOffsets,
            final MdlxGeoset geoset,
            final Matrix4f[] worldTransforms) {

        if (groupIdx >= geoset.matrixGroups.length || geoset.matrixIndices == null) {
            return new float[]{vx, vy, vz};
        }

        final long start = matrixGroupOffsets[groupIdx];
        final long count = geoset.matrixGroups[groupIdx];

        if (count == 0) return new float[]{vx, vy, vz};

        // For simplicity take the first bone (works for >95% of WC3 models)
        final int boneObjectId = (int) geoset.matrixIndices[(int) start];

        final Integer nodeIdx = nodeIndexById.get(boneObjectId);
        if (nodeIdx == null || nodeIdx >= worldTransforms.length) {
            return new float[]{vx, vy, vz};
        }
        // worldTransforms[nodeIdx] already encodes: translate(T+pivot)*R*S*translate(-pivot)
        // so we just multiply the vertex by it
        return worldTransforms[nodeIdx].transformPoint(vx, vy, vz);
    }

    private Matrix4f[] buildWorldTransforms(final long frame, final long seqStart, final long seqEnd) {
        final Matrix4f[] world = new Matrix4f[allNodes.size()];

        // Iterate in topological order so every parent is computed before its children.
        for (final int i : topoOrder) {
            final MdlxGenericObject node = allNodes.get(i);
            final float[] pivot = getPivot(node.objectId);
            final float[] trans = getTimeline3(node, AnimationMap.KGTR, frame, seqStart, seqEnd, IDENTITY_TRANS);
            final float[] rot   = getTimeline4(node, AnimationMap.KGRT, frame, seqStart, seqEnd, IDENTITY_ROT);
            final float[] sc    = getTimeline3(node, AnimationMap.KGSC, frame, seqStart, seqEnd, IDENTITY_SCALE);

            final Matrix4f local = Matrix4f.boneLocal(trans, rot, sc, pivot);
            final int parentIdx = parentIndices[i];
            world[i] = (parentIdx < 0 || world[parentIdx] == null)
                    ? local
                    : Matrix4f.multiply(world[parentIdx], local);
        }
        return world;
    }

    // -------------------------------------------------------------------------
    // Timeline sampling
    // -------------------------------------------------------------------------

    private static final War3ID KGTR_ID = AnimationMap.KGTR.getWar3id();
    private static final War3ID KGRT_ID = AnimationMap.KGRT.getWar3id();
    private static final War3ID KGSC_ID = AnimationMap.KGSC.getWar3id();

    @SuppressWarnings("unchecked")
    private float[] getTimeline3(final MdlxGenericObject node, final AnimationMap map,
                                  final long frame, final long seqStart, final long seqEnd,
                                  final float[] defaultVal) {
        final War3ID id = map.getWar3id();
        for (final MdlxTimeline<?> tl : node.timelines) {
            if (tl.name.equals(id) && tl instanceof MdlxFloatArrayTimeline fat) {
                return sampleFloat3(fat, frame, seqStart, seqEnd, defaultVal);
            }
        }
        return defaultVal;
    }

    @SuppressWarnings("unchecked")
    private float[] getTimeline4(final MdlxGenericObject node, final AnimationMap map,
                                  final long frame, final long seqStart, final long seqEnd,
                                  final float[] defaultVal) {
        final War3ID id = map.getWar3id();
        for (final MdlxTimeline<?> tl : node.timelines) {
            if (tl.name.equals(id) && tl instanceof MdlxFloatArrayTimeline fat) {
                return sampleFloat4(fat, frame, seqStart, seqEnd, defaultVal);
            }
        }
        return defaultVal;
    }

    private static float[] sampleFloat3(final MdlxFloatArrayTimeline tl, final long frame,
                                         final long seqStart, final long seqEnd,
                                         final float[] defaultVal) {
        if (tl.frames == null || tl.frames.length == 0) return defaultVal;
        return sampleFloatArray(tl, frame, seqStart, seqEnd, defaultVal, 3);
    }

    private static float[] sampleFloat4(final MdlxFloatArrayTimeline tl, final long frame,
                                         final long seqStart, final long seqEnd,
                                         final float[] defaultVal) {
        if (tl.frames == null || tl.frames.length == 0) return defaultVal;
        return sampleFloatArray(tl, frame, seqStart, seqEnd, defaultVal, 4);
    }

    private static float[] sampleFloatArray(final MdlxFloatArrayTimeline tl, final long frame,
                                             final long seqStart, final long seqEnd,
                                             final float[] defaultVal, final int size) {
        final long[] frames = tl.frames;
        final float[][] values = tl.values;

        // Locate the first keyframe within [seqStart, seqEnd] via binary search
        int firstIdx;
        {
            int lo = 0, hi = frames.length;
            while (lo < hi) { final int mid = (lo + hi) >>> 1; if (frames[mid] < seqStart) lo = mid + 1; else hi = mid; }
            firstIdx = lo;
        }
        if (firstIdx >= frames.length || frames[firstIdx] > seqEnd) return defaultVal;

        // Locate the last keyframe within [seqStart, seqEnd]
        int lastIdx;
        {
            int lo = firstIdx, hi = frames.length - 1;
            while (lo < hi) { final int mid = (lo + hi + 1) >>> 1; if (frames[mid] <= seqEnd) lo = mid; else hi = mid - 1; }
            lastIdx = lo;
        }

        // Before (or at) the first key in this sequence
        if (frame <= frames[firstIdx]) return values[firstIdx];

        // After the last key in this sequence: interpolate toward first key to loop cleanly
        if (frame > frames[lastIdx]) {
            if (tl.interpolationType == InterpolationType.DONT_INTERP || frames[lastIdx] >= seqEnd) {
                return values[lastIdx];
            }
            final float t = (float) (frame - frames[lastIdx]) / (float) (seqEnd - frames[lastIdx]);
            return interpolate(values[lastIdx], values[firstIdx], t, size);
        }

        // Normal case: find the bracket within the sequence
        int lo = firstIdx, hi = lastIdx;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (frames[mid] <= frame) lo = mid; else hi = mid;
        }

        if (tl.interpolationType == InterpolationType.DONT_INTERP) {
            return values[lo];
        }

        final float t = (float) (frame - frames[lo]) / (float) (frames[hi] - frames[lo]);
        return interpolate(values[lo], values[hi], t, size);
    }

    private static float[] interpolate(final float[] a, final float[] b, final float t, final int size) {
        if (size == 4) return slerp(a, b, t);
        final float[] out = new float[size];
        for (int i = 0; i < size; i++) out[i] = a[i] + t * (b[i] - a[i]);
        return out;
    }

    /** Spherical linear interpolation for unit quaternions. */
    private static float[] slerp(final float[] a, final float[] b, final float t) {
        float dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3];
        float bx = b[0], by = b[1], bz = b[2], bw = b[3];
        if (dot < 0f) { dot = -dot; bx=-bx; by=-by; bz=-bz; bw=-bw; }

        if (dot > 0.9995f) {
            // Quaternions nearly identical: use normalized lerp
            final float s = 1f - t;
            final float ox = s * a[0] + t * bx;
            final float oy = s * a[1] + t * by;
            final float oz = s * a[2] + t * bz;
            final float ow = s * a[3] + t * bw;
            final float len = (float) Math.sqrt(ox*ox + oy*oy + oz*oz + ow*ow);
            return new float[]{ox/len, oy/len, oz/len, ow/len};
        }

        final float theta0 = (float) Math.acos(dot);
        final float theta  = theta0 * t;
        final float sinTheta0 = (float) Math.sin(theta0);
        final float s1 = (float) Math.cos(theta) - dot * (float) Math.sin(theta) / sinTheta0;
        final float s2 = (float) Math.sin(theta) / sinTheta0;
        return new float[]{
            s1 * a[0] + s2 * bx,
            s1 * a[1] + s2 * by,
            s1 * a[2] + s2 * bz,
            s1 * a[3] + s2 * bw
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private float[] getPivot(final int objectId) {
        if (objectId >= 0 && objectId < model.pivotPoints.size()) {
            return model.pivotPoints.get(objectId);
        }
        return new float[]{0f, 0f, 0f};
    }

    private static List<MdlxGenericObject> buildNodeList(final MdlxModel model) {
        final List<MdlxGenericObject> all = new ArrayList<>();
        all.addAll(model.bones);
        all.addAll(model.lights);
        all.addAll(model.helpers);
        all.addAll(model.attachments);
        all.addAll(model.particleEmitters);
        all.addAll(model.particleEmitters2);
        all.addAll(model.ribbonEmitters);
        all.addAll(model.eventObjects);
        all.addAll(model.collisionShapes);
        all.sort(Comparator.comparingInt(n -> n.objectId));
        return all;
    }

    private int[] buildParentIndices() {
        final int[] idx = new int[allNodes.size()];
        for (int i = 0; i < allNodes.size(); i++) {
            final int parentId = allNodes.get(i).parentId;
            idx[i] = (parentId < 0) ? -1 : nodeIndexById.getOrDefault(parentId, -1);
        }
        return idx;
    }

    /**
     * Kahn's topological sort — returns node indices such that every parent
     * appears before all of its children. Handles broken/cyclic refs gracefully.
     */
    private int[] buildTopologicalOrder() {
        final int n = allNodes.size();
        final int[] inDegree = new int[n];
        final List<List<Integer>> children = new ArrayList<>(n);
        for (int i = 0; i < n; i++) children.add(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            final int p = parentIndices[i];
            if (p >= 0) {
                inDegree[i]++;
                children.get(p).add(i);
            }
        }

        final Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) queue.add(i);
        }

        final int[] order = new int[n];
        int tail = 0;
        while (!queue.isEmpty()) {
            final int curr = queue.poll();
            order[tail++] = curr;
            for (final int child : children.get(curr)) {
                if (--inDegree[child] == 0) queue.add(child);
            }
        }

        // Fallback: include any nodes left out due to cycles or broken parent refs
        for (int i = 0; i < n; i++) {
            if (inDegree[i] > 0) order[tail++] = i;
        }
        return order;
    }
}
