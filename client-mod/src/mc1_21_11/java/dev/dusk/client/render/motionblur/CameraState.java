package dev.dusk.client.render.motionblur;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The camera basis for one frame, kept exactly as natural-motionblur does:
 * the inverse of this frame's matrices plus the previous frame's matrices,
 * which is everything the velocity shader needs to reproject a pixel.
 */
public final class CameraState {

    private final Matrix4f mvInverse = new Matrix4f();
    private final Matrix4f projInverse = new Matrix4f();
    private final Matrix4f prevModelView = new Matrix4f();
    private final Matrix4f prevProjection = new Matrix4f();
    private float dx, dy, dz;

    public void setFrame(Matrix4fc modelView, Matrix4fc prevModelView,
                         Matrix4fc projection, Matrix4fc prevProjection,
                         float dx, float dy, float dz) {
        modelView.invert(this.mvInverse);
        projection.invert(this.projInverse);
        this.prevModelView.set(prevModelView);
        this.prevProjection.set(prevProjection);
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public Matrix4f getMvInverse() { return mvInverse; }
    public Matrix4f getProjInverse() { return projInverse; }
    public Matrix4f getPrevModelView() { return prevModelView; }
    public Matrix4f getPrevProjection() { return prevProjection; }
    public float getDx() { return dx; }
    public float getDy() { return dy; }
    public float getDz() { return dz; }
}
