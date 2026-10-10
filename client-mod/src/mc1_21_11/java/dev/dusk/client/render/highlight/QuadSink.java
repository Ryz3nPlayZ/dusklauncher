package dev.dusk.client.render.highlight;

import org.joml.Vector3fc;

/** Takes a block model's quads, corner by corner, in block space. */
@FunctionalInterface
public interface QuadSink {
    void quad(Vector3fc p0, Vector3fc p1, Vector3fc p2, Vector3fc p3);
}
