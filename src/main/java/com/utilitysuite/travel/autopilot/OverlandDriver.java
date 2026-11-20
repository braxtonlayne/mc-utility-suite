package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.NavigationStatus;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Overland travel: delegates each stop to {@link INavigator#goTo} (the pathfinder drives the player itself) and watches
 * its status. "x z" stops take the surface height when the column is loaded, else the player's height.
 */
final class OverlandDriver {

    enum Result { RUNNING, ARRIVED, FAILED }

    private static final int IDLE_GRACE_TICKS = 40;

    private boolean issued;
    private int idleTicks;
    private int arrivalRadius = 3;

    void configure(int arrivalRadius) {
        this.arrivalRadius = arrivalRadius;
    }

    void begin() {
        issued = false;
        idleTicks = 0;
    }

    Result tick(LocalPlayer player, RouteState route, INavigator nav) {
        if (!issued) {
            int y = route.y();
            if (!route.hasY()) {
                y = player.level().hasChunk(route.x() >> 4, route.z() >> 4)
                        ? player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, route.x(), route.z())
                        : player.getBlockY();
            }
            nav.goTo(route.x(), y, route.z(), arrivalRadius);
            issued = true;
            idleTicks = 0;
            return Result.RUNNING;
        }
        NavigationStatus s = nav.status();
        switch (s) {
            case ARRIVED -> {
                if (route.advance()) {
                    issued = false;
                    return Result.RUNNING;
                }
                return Result.ARRIVED;
            }
            case FAILED, UNAVAILABLE -> {
                return Result.FAILED;
            }
            case IDLE -> {
                // Pathing was cancelled elsewhere (or never started); give it a moment, then give up.
                if (++idleTicks > IDLE_GRACE_TICKS) {
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
