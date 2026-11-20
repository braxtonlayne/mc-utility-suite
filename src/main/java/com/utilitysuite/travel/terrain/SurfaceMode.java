package com.utilitysuite.travel.terrain;

/** How surface traversal ("Water Walk") keeps the player on top of a fluid. */
public enum SurfaceMode {
    /** Source fluid blocks collide like a solid top for the local player (single-player / companion servers). */
    SOLID_COLLISION,
    /** Gentle jump timing keeps the player bobbing at the surface; no collision change. */
    BOBBING
}
