package com.utilitysuite.core.api.visual;

/**
 * 3D drawing in theme tokens with camera-relative float coordinates, valid only inside the level-render pass of the
 * Shell. Lines are soft ribbons that hold a constant width on screen (plain lines when effects are off or a shader
 * pack is active). Allocation free; the Shell caps the segments per frame and drops the farthest first.
 */
public interface IHoloWorld {

    void ribbon(float x0, float y0, float z0, float x1, float y1, float z1, HoloToken token, float alpha);

    /** {@code count} points as x, y, z triples in {@code xyz}. {@code dash} above 0 draws dashes of that length. */
    void polyline(float[] xyz, int count, float dash, HoloToken token, float alpha);

    /** A horizontal ring. */
    void ring(float cx, float cy, float cz, float radius, HoloToken token, float alpha);

    void boxEdges(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, HoloToken token,
                  float alpha);

    /** Corner brackets on the top face of a box (a landing top). */
    void topBrackets(float minX, float minZ, float maxX, float maxZ, float y, float arm, HoloToken token,
                     float alpha);

    /** A small bracketed mark lying on the ground at a point. */
    void groundMark(float x, float y, float z, float size, HoloToken token, float alpha);
}
