package com.utilitysuite.travel.pursuit;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IBodySimProvider;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.travel.movement.EscapePlanner;
import com.utilitysuite.travel.movement.LevelVoxels;
import com.utilitysuite.travel.movement.MovementPhysics;
import com.utilitysuite.travel.movement.RouteFollower;
import com.utilitysuite.travel.movement.TickWorldShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Minecraft-facing route runner: fills the body from the local player, adapts {@link RouteFollower} and
 * {@link EscapePlanner} to the core's small interfaces and ticks the core from the client tick (the handler returns
 * immediately while idle). Client thread only.
 */
public final class MinecraftRouteRunner implements IRouteRunner {

    /** Assumed chaser speed (blocks per tick) for the local escape plan; it can follow over gaps. */
    private static final double THREAT_SPEED = 0.25;

    private final Body body;
    private final RouteRunnerCore core;
    private final RouteFollower follower;

    /**
     * @param shared the tick's exact-shape view (shared with Movement Assist)
     * @param mode   whose {@link IBodySimProvider#exactShapes} picks the view: the full-cube {@link LevelVoxels}
     *               (default) or {@code shared}, so pursuit follows the combat "Exact world shapes" setting
     */
    public MinecraftRouteRunner(TickWorldShapes shared, IBodySimProvider mode) {
        body = new Body(shared, mode);
        follower = new RouteFollower();
        EscapePlanner escape = new EscapePlanner();
        Steering steering = new FollowerSteering(follower, body);
        LocalFlee localFlee = (x, y, z, out) -> {
            escape.clearThreats();
            escape.addThreat(x, y, z, THREAT_SPEED, true);
            if (!body.refreshed || !escape.plan(body.x, body.y, body.z, body.voxels)) {
                return 0;
            }
            int n = Math.min(escape.pathLength(), out.length);
            for (int i = 0; i < n; i++) {
                out[i] = escape.pathCell(i);
            }
            return n;
        };
        core = new RouteRunnerCore(body, steering, localFlee);
    }

    /** True while the run walks a route (read-only, for overlays). */
    public boolean isFollowingRoute() {
        return core.followingRoute();
    }

    /** Copies the current route's packed cells into the caller's array (call once per tick); returns the count. */
    public int copyRoute(long[] out) {
        int n = Math.min(follower.length(), out.length);
        for (int i = 0; i < n; i++) {
            out[i] = follower.cell(i);
        }
        return n;
    }

    /** Index of the route cell the body has reached. */
    public int routeIndex() {
        return follower.progressIndex();
    }

    /** Client tick listener (register once). */
    public void onTick(ClientTickEvent.Post event) {
        if (core.idle()) {
            return;
        }
        core.bind(Services.input(), Services.routePlannerOrNull());
        core.tick();
    }

    private void bind() {
        core.bind(Services.input(), Services.routePlannerOrNull());
    }

    @Override
    public boolean isAvailable() {
        return core.isAvailable();
    }

    @Override
    public boolean pursue(ControlLease lease, double x, double y, double z, double stopRange, boolean lookAlongRoute) {
        bind();
        return core.pursue(lease, x, y, z, stopRange, lookAlongRoute);
    }

    @Override
    public boolean flee(ControlLease lease, double fromX, double fromY, double fromZ, double minDistance,
                        boolean lookAlongRoute) {
        bind();
        return core.flee(lease, fromX, fromY, fromZ, minDistance, lookAlongRoute);
    }

    @Override
    public void stop(ControlLease lease) {
        bind();
        core.stop(lease);
    }

    @Override
    public State state() {
        return core.state();
    }

    @Override
    public boolean isRunning(ControlLease lease) {
        return core.isRunning(lease);
    }

    /** Fills a {@link MovementPhysics} from the local player once per tick (as MovementAssist does). */
    private static final class Body implements BodySource {
        final MovementPhysics now = new MovementPhysics();
        private final LevelVoxels cubes = new LevelVoxels();
        private final TickWorldShapes shared;
        private final IBodySimProvider mode;
        /** This tick's view: {@link #cubes} or the shared exact shapes. */
        MovementPhysics.Voxels voxels = cubes;
        private final BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();

        Body(TickWorldShapes shared, IBodySimProvider mode) {
            this.shared = shared;
            this.mode = mode;
        }
        double x, y, z;
        float yaw, health;
        boolean refreshed;

        @Override
        public boolean available() {
            return Minecraft.getInstance().player != null;
        }

        @Override
        public boolean refresh() {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) {
                refreshed = false;
                return false;
            }
            var v = p.getDeltaMovement();
            double attr = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
            x = p.getX();
            y = p.getY();
            z = p.getZ();
            yaw = p.getYRot();
            health = p.getHealth();
            now.set(x, y, z, v.x, v.y, v.z, p.onGround());
            now.speed = p.isSprinting() ? attr / 1.3 : attr; // the attribute already carries the sprint modifier
            now.jumpBoost = p.getJumpBoostPower();
            now.fallDistance = p.fallDistance;
            below.set(x, p.getBoundingBox().minY - 0.500001, z);
            now.slipperiness = mc.level.getBlockState(below).getBlock().getFriction();
            voxels = mode.exactShapes() ? shared.forTick(mc.level) : cubes.begin(mc.level);
            refreshed = true;
            return true;
        }

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return y;
        }

        @Override
        public double z() {
            return z;
        }

        @Override
        public float yaw() {
            return yaw;
        }

        @Override
        public float health() {
            return health;
        }
    }

    /** Adapts the real follower to the core's {@link Steering}. */
    private static final class FollowerSteering implements Steering {
        private final RouteFollower f;
        private final Body body;

        FollowerSteering(RouteFollower f, Body body) {
            this.f = f;
            this.body = body;
        }

        @Override
        public void setRoute(long[] packed, int count) {
            f.setRoute(packed, count);
        }

        @Override
        public void clear() {
            f.clear();
        }

        @Override
        public boolean hasRoute() {
            return f.hasRoute();
        }

        @Override
        public void update(float yaw, float health) {
            f.update(body.now, yaw, health, body.voxels);
        }

        @Override
        public int forward() {
            return f.forward();
        }

        @Override
        public int strafe() {
            return f.strafe();
        }

        @Override
        public boolean jump() {
            return f.jump();
        }

        @Override
        public boolean sprint() {
            return f.sprint();
        }

        @Override
        public float wantYaw() {
            return f.wantYaw();
        }

        @Override
        public boolean finished() {
            return f.finished();
        }

        @Override
        public boolean offRoute() {
            return f.offRoute();
        }
    }
}
