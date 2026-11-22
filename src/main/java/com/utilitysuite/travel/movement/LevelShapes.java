package com.utilitysuite.travel.movement;

import net.minecraft.world.level.Level;

/**
 * {@link MovementPhysics.Voxels} over the client level with exact collision shapes: the per-tick cell cache that the
 * parkour control model plans on (fence and wall tops at 1.5, panes, slabs, surface kinds). Client thread only;
 * {@link #begin} once per tick.
 * <p>
 * Contract placeholder: this version answers with the full-cube approximation of {@link LevelVoxels} so callers can
 * already be written against the class. The shape-physics work replaces the body (shape id cache, per-state box
 * table, surface byte, UNMODELLED marking) and keeps this API.
 */
public final class LevelShapes implements MovementPhysics.Voxels {

    private final LevelVoxels cubes = new LevelVoxels();

    public LevelShapes begin(Level level) {
        cubes.begin(level);
        return this;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return cubes.solid(x, y, z);
    }

    @Override
    public boolean fullBlock(int x, int y, int z) {
        return cubes.fullBlock(x, y, z);
    }
}
