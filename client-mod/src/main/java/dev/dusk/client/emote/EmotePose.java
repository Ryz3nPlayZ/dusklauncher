package dev.dusk.client.emote;

import dev.dusk.client.emote.EmoteAnimation.Bone;

/**
 * One frame of an emote: per bone, a rotation in degrees and an offset in
 * model pixels, both as the animation file wrote them (Bedrock axes). Bones
 * the emote doesn't animate stay unset and keep vanilla's pose.
 */
public final class EmotePose {
    private final float[][] rot = new float[Bone.values().length][];
    private final float[][] pos = new float[Bone.values().length][];

    void rotate(Bone bone, float x, float y, float z) {
        rot[bone.ordinal()] = new float[] {x, y, z};
    }

    void move(Bone bone, float x, float y, float z) {
        pos[bone.ordinal()] = new float[] {x, y, z};
    }

    /** Degrees about x, y, z in the file's axes, or null when the bone isn't animated. */
    public float[] rotation(Bone bone) { return rot[bone.ordinal()]; }

    /** Pixels along x, y, z in the file's axes, or null when the bone isn't moved. */
    public float[] offset(Bone bone) { return pos[bone.ordinal()]; }
}
