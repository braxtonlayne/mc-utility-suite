package com.utilitysuite.travel.movement;

import net.minecraft.world.level.Level;

/**
 * The one exact-shape world view of a client tick: a single {@link LevelShapes} that Movement Assist, the shared body
 * sims ({@link WorldBodySims}) and the pursuit runner all read, so a cell is read from the level at most once per tick
 * and every reader sees the same answer. Owned by the travel bootstrap. {@link #beginTick} on
 * {@code ClientTickEvent.Pre} only advances a counter; the cache is cleared by the first {@link #forTick} call of the
 * tick (so an idle tick costs nothing). Client thread only; allocation free.
 */
public final class TickWorldShapes {

    private final LevelShapes shapes = new LevelShapes();
    private long tick;
    private long begunTick = -1;
    private Level begunLevel;

    /** ClientTickEvent.Pre: the next {@link #forTick} starts a fresh cell cache. */
    public void beginTick() {
        tick++;
    }

    /** This tick's view over {@code level}: the first call of a tick (or after a level change) clears the cache. */
    public LevelShapes forTick(Level level) {
        if (begunTick != tick || begunLevel != level) {
            begunTick = tick;
            begunLevel = level;
            shapes.begin(level);
        }
        return shapes;
    }

    /** The view as last begun, without starting a tick (render-time reads of this tick's cells). */
    public LevelShapes shapes() {
        return shapes;
    }
}
