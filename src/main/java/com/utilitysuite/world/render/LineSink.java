package com.utilitysuite.world.render;

/**
 * Engine-free target for the overlay geometry emitters ({@link Shapes}). The game implementation is
 * {@link PoseLineSink} (one reused instance writing into the shared line batch); tests use a counting sink. All
 * coordinates are camera-relative.
 */
public interface LineSink {

    void line(float x1, float y1, float z1, float x2, float y2, float z2, int argb);
}
