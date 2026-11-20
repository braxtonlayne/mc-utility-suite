package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.NavigationStatus;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Optional;

/**
 * Overland travel: delegates each stop to {@link INavigator#goToLeased} (the pathfinder drives the player itself while
 * the autopilot holds a TRAVEL lease on movement, camera and world interaction) and watches the run's status. When the
 * lease is preempted, overridden by the user or revoked by Stop-All the run cancels the pathfinder and the driver
 * reports {@link Result#INTERRUPTED}. "x z" stops take the surface height when the column is loaded, else the player's
 * height.
 */
final class OverlandDriver {

    enum Result { RUNNING, ARRIVED, FAILED, INTERRUPTED }

    private static final int IDLE_GRACE_TICKS = 40;

    private boolean issued;
    private int idleTicks;
    private int arrivalRadius = 3;
    private NavigationRun current;

    void configure(int arrivalRadius) {
        this.arrivalRadius = arrivalRadius;
    }

    void begin() {
        cancel();
        issued = false;
        idleTicks = 0;
    }

    /** Cancels the running leg (pathfinder + lease), if any. */
    void cancel() {
        NavigationRun r = current;
        current = null;
        if (r != null) {
            r.cancel();
        }
    }

    Result tick(LocalPlayer player, RouteState route, INavigator nav) {
        if (!issued) {
            int y = route.y();
            if (!route.hasY()) {
                y = player.level().hasChunk(route.x() >> 4, route.z() >> 4)
                        ? player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, route.x(), route.z())
                        : player.getBlockY();
            }
            Optional<NavigationRun> r = nav.goToLeased(TravelAutopilot.ID, ControlPriority.TRAVEL, route.x(), y,
                    route.z(), arrivalRadius);
            if (r.isEmpty()) {
                // Movement held by something more important, the user, or automation is stopped.
                return Result.INTERRUPTED;
            }
            current = r.get();
            issued = true;
            idleTicks = 0;
            return Result.RUNNING;
        }
        Result result = step(current);
        if (result == Result.ARRIVED && route.advance()) {
            issued = false;
            return Result.RUNNING;
        }
        return result;
    }

    /** Status handling for the current leg; engine-agnostic so it is unit-testable. */
    Result step(NavigationRun r) {
        if (r == null) {
            return Result.FAILED;
        }
        if (r.endReason() != null) {
            current = null;
            return Result.INTERRUPTED;
        }
        NavigationStatus s = r.status();
        if (r.endReason() != null) {
            current = null;
            return Result.INTERRUPTED;
        }
        switch (s) {
            case ARRIVED -> {
                current = null;
                return Result.ARRIVED;
            }
            case FAILED, UNAVAILABLE -> {
                current = null;
                return Result.FAILED;
            }
            case IDLE -> {
                // Pathing was cancelled elsewhere (or never started); give it a moment, then give up.
                if (++idleTicks > IDLE_GRACE_TICKS) {
                    current = null;
                    return Result.FAILED;
                }
                return Result.RUNNING;
            }
            default -> {
                idleTicks = 0;
                return Result.RUNNING;
            }
        }
    }
}
