package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.BodySim;
import com.utilitysuite.core.api.IBodySimProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * {@link IBodySimProvider} over the client level. Every body reads one {@link WorldViewSwitch}:
 * <ul>
 *   <li>by default the full-cube view, a {@link LevelVoxels} cache refreshed lazily the first time a body reads a block
 *       in a new client tick (an idle provider costs one counter increment per tick);</li>
 *   <li>with {@link #useExactShapes} on, the shared {@link TickWorldShapes} view that Movement Assist plans on (exact
 *       shapes, step-up, surfaces), begun at most once per tick by whichever reader comes first.</li>
 * </ul>
 * Outside a world every block reads as air. Client thread only.
 */
public final class WorldBodySims implements IBodySimProvider {

    private final LevelVoxels voxels = new LevelVoxels();
    private final TickWorldShapes shared;
    private final WorldViewSwitch view;
    private long tick;
    private long cachedTick = -1;
    private boolean ready;

    public WorldBodySims(TickWorldShapes shared) {
        this.shared = shared;
        this.view = new WorldViewSwitch(new Cubes(), new Exact());
    }

    /** ClientTickEvent.Pre: the next block read starts a fresh cube cache. */
    public void beginTick() {
        tick++;
    }

    @Override
    public BodySim newBody() {
        return new PhysicsBodySim(view);
    }

    @Override
    public void useExactShapes(boolean on) {
        view.useExact(on);
    }

    @Override
    public boolean exactShapes() {
        return view.usingExact();
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

    /** Full-cube view (the default prediction path). */
    private final class Cubes implements MovementPhysics.Voxels {
        @Override
        public boolean solid(int x, int y, int z) {
            return refresh() && voxels.solid(x, y, z);
        }

        @Override
        public boolean fullBlock(int x, int y, int z) {
            return refresh() && voxels.fullBlock(x, y, z);
        }

        @Override
        public boolean replaceable(int x, int y, int z) {
            return refresh() && voxels.replaceable(x, y, z);
        }
    }

    /** The shared exact-shape view; all air outside a world. */
    private final class Exact implements MovementPhysics.Voxels {
        private LevelShapes now() {
            Level level = Minecraft.getInstance().level;
            return level == null ? null : shared.forTick(level);
        }

        @Override
        public boolean shaped() {
            return true;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            LevelShapes s = now();
            return s != null && s.solid(x, y, z);
        }

        @Override
        public boolean fullBlock(int x, int y, int z) {
            LevelShapes s = now();
            return s != null && s.fullBlock(x, y, z);
        }

        @Override
        public boolean replaceable(int x, int y, int z) {
            LevelShapes s = now();
            return s != null && s.replaceable(x, y, z);
        }

        @Override
        public int boxCount(int x, int y, int z) {
            LevelShapes s = now();
            return s == null ? 0 : s.boxCount(x, y, z);
        }

        @Override
        public void box(int x, int y, int z, int i, float[] out, int off) {
            LevelShapes s = now();
            if (s != null) {
                s.box(x, y, z, i, out, off);
            }
        }

        @Override
        public byte surface(int x, int y, int z) {
            LevelShapes s = now();
            return s == null ? MovementPhysics.SURFACE_NONE : s.surface(x, y, z);
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            LevelShapes s = now();
            return s == null ? (float) MovementPhysics.DEFAULT_SLIPPERINESS : s.slipperiness(x, y, z);
        }
    }
}
