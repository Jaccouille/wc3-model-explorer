package com.hiveworkshop.model;

/** An animation sequence from the MDX model. */
public record SequenceInfo(
        String name, long start, long end, int flags,
        float[] minExtent, float[] maxExtent, float boundsRadius
) {

    /** Backwards-compatible constructor without flags or extents. */
    public SequenceInfo(String name, long start, long end) {
        this(name, start, end, 0, null, null, 0f);
    }

    /** Backwards-compatible constructor without extents. */
    public SequenceInfo(String name, long start, long end, int flags) {
        this(name, start, end, flags, null, null, 0f);
    }

    public long duration() {
        return end - start;
    }

    /** True if the sequence has the NONLOOPING flag (bit 0). */
    public boolean isNonLooping() {
        return (flags & 1) != 0;
    }

    /** True if this sequence has valid extent data. */
    public boolean hasExtent() {
        return minExtent != null && maxExtent != null
                && minExtent.length >= 3 && maxExtent.length >= 3;
    }

    public float extentCenterX() { return hasExtent() ? (minExtent[0] + maxExtent[0]) * 0.5f : 0f; }
    public float extentCenterY() { return hasExtent() ? (minExtent[1] + maxExtent[1]) * 0.5f : 0f; }
    public float extentCenterZ() { return hasExtent() ? (minExtent[2] + maxExtent[2]) * 0.5f : 0f; }

    public float extentRadius() {
        if (!hasExtent()) return 0f;
        float dx = maxExtent[0] - minExtent[0];
        float dy = maxExtent[1] - minExtent[1];
        float dz = maxExtent[2] - minExtent[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f;
    }

    /** Display label shown in the UI combo box. */
    public String displayLabel() {
        return name + "  (" + duration() + " ms)" + (isNonLooping() ? " [NL]" : "");
    }

    @Override
    public String toString() {
        return displayLabel();
    }
}
