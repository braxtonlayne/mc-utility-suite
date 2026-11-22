package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.BodySim;
import com.utilitysuite.core.api.IBodySimProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * {@link IBodySimProvider} over the client level: every body shares one {@link LevelVoxels} cache, refreshed lazily
 * the first time a body reads a block in a new client tick (an idle provider costs one counter increment per tick).
 * Outside a world every block reads as air. Client thread only.
 */
public final class WorldBodySims implements IBodySimProvider, MovementPhysics.Voxels {

    private final LevelVoxels voxels = new LevelVoxels();
    private long tick;
    private long cachedTick = -1;
    private boolean ready;

    /** ClientTickEvent.Pre: the next block read starts a fresh cache. */
    public void beginTick() {
        tick++;
    }

    @Override
    public BodySim newBody() {
        return new PhysicsBodySim(this);
    }

    private boolean refresh() {
        if (cachedTick != tick) {
            cachedTick = tick;
            Level level = Minecraft.getInstance().level;
            ready = level != null;
            if (ready) {
                voxels.begin(level);
            }
        }
        return ready;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return refresh() && voxels.solid(x, y, z);
    }

    @Override
    public boolean fullBlock(int x, int y, int z) {
        return refresh() && voxels.fullBlock(x, y, z);
    }
}
