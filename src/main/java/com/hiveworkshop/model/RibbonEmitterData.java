package com.hiveworkshop.model;

/**
 * Parsed ribbon emitter data from an MDX/MDL model.
 *
 * <p>A ribbon emitter is attached to a skeleton node (identified by {@code objectId})
 * and spawns a continuous trail of ribbon segments that follow the emitter's world
 * position over time. Each segment has an "above" and "below" edge relative to the
 * emitter's local Y axis, creating a flat strip.
 *
 * @param objectId      node objectId (used to look up world transform from BoneAnimator)
 * @param materialId    index into the model's materials array
 * @param heightAbove   static height above the emitter pivot (local +Y)
 * @param heightBelow   static height below the emitter pivot (local -Y)
 * @param alpha         static alpha (0–1)
 * @param color         static color [R, G, B] (converted from WC3 BGR during parsing)
 * @param lifeSpan      particle lifetime in seconds
 * @param gravity       downward acceleration applied to particles
 * @param emissionRate  particles spawned per second
 * @param rows          texture atlas row count
 * @param columns       texture atlas column count
 * @param textureSlot   texture atlas slot index
 * @param heightAboveTrack  KRHA – animated heightAbove
 * @param heightBelowTrack  KRHB – animated heightBelow
 * @param alphaTrack        KRAL – animated alpha
 * @param colorTrack        KRCO – animated color (vec3 BGR)
 * @param visibilityTrack   KRVS – animated visibility (float, 0 or 1)
 * @param texSlotTrack      KRTX – animated texture slot
 */
public record RibbonEmitterData(
        int objectId,
        int materialId,
        float heightAbove,
        float heightBelow,
        float alpha,
        float[] color,
        float lifeSpan,
        float gravity,
        int emissionRate,
        int rows,
        int columns,
        int textureSlot,
        AnimTrack heightAboveTrack,
        AnimTrack heightBelowTrack,
        AnimTrack alphaTrack,
        AnimTrack colorTrack,
        AnimTrack visibilityTrack,
        AnimTrack texSlotTrack
) {
    public static final RibbonEmitterData[] EMPTY_ARRAY = new RibbonEmitterData[0];
}
