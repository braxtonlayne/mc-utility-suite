package com.utilitysuite.devtest;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.travel.bridge.BridgeAssist;
import com.utilitysuite.travel.terrain.TerrainAssist;

/** Scenario 8: SafeWalk at a cliff edge, Bridge Assist with a block budget, Water Walk solid mode. */
final class TravelScenario {

    private TravelScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("8-travel", "Travel: SafeWalk, bridge, water walk");
        ControlLease[] lease = new ControlLease[1];
        int[] bridgeStart = new int[1];
        Arena.ensure(s);
        s.run("enable terrain assist (SafeWalk)", () -> {
            H.setting(TerrainAssist.ID, "safewalk", true);
            H.enable(TerrainAssist.ID, true);
        });
        // --- SafeWalk: hold FORWARD (by lease) toward the south edge ---
        s.cmd(() -> Arena.tp(0.5, 7.5, 0f));
        s.waitTicks(10);
        s.run("hold FORWARD by lease", () -> {
            lease[0] = Services.arbiter().request(LeaseRequest.of("playtest_mover", ControlPriority.TRAVEL, null,
                    ControlChannel.MOVEMENT)).orElseThrow();
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, true);
        });
        s.waitTicks(80);
        s.run("SafeWalk result", () -> {
            double z = H.p().getZ() - Arena.cz;
            double y = H.p().getY();
            boolean onDeck = y >= Arena.FLOOR + 0.99 && z <= 13.4;
            s.check("SafeWalk stops at the cliff edge with FORWARD held", onDeck,
                    Harness.fmt("player at z=cz%+.2f (edge block ends at +13.0), y=%.2f, keyUp=%s", z, y,
                            H.mc().options.keyUp.isDown()));
            lease[0].close();
        });
        // --- Bridge: budget 5 eastward over the gap ---
        s.cmd("clear @s");
        s.cmd("give @s minecraft:cobblestone 64");
        s.cmd(() -> Arena.tp(11.5, 0.5, -90f));
        s.waitTicks(10);
        s.run("start bridge", () -> {
            H.setting(BridgeAssist.ID, "budget", 5);
            H.setting(BridgeAssist.ID, "length", 32);
            H.setting(BridgeAssist.ID, "hotbar_reserve", 0);
            H.enable(BridgeAssist.ID, true);
            bridgeStart[0] = H.count("minecraft:cobblestone");
            boolean started = TravelBootstrap.bridge().start();
            s.check("bridge starts", started, "running=" + TravelBootstrap.bridge().isRunning());
        });
        s.waitUpTo("bridge stops", 20 * 40, () -> !TravelBootstrap.bridge().isRunning());
        s.run("bridge result", () -> {
            int placed = 0;
            for (int dx = 13; dx <= 20; dx++) {
                if (!H.block(Arena.cx + dx, Arena.FLOOR, Arena.cz).isAir()) {
                    placed++;
                }
            }
            int used = bridgeStart[0] - H.count("minecraft:cobblestone");
            s.check("bridge places blocks over the gap and stops at the budget (5)",
                    !TravelBootstrap.bridge().isRunning() && placed == 5 && used == 5,
                    "deck blocks east of the edge: " + placed + ", cobblestone used " + used + ", running="
                            + TravelBootstrap.bridge().isRunning() + ", player " + H.posStr() + "; " + H.arbiterState());
        });
        // --- Water walk ---
        s.cmd(() -> Arena.tp(6.5, -6.5, 0f));
        s.run("surface mode", () -> {
            H.setting(TerrainAssist.ID, "surface_mode", "SOLID_COLLISION");
            TerrainAssist t = TravelBootstrap.terrain();
            if (!t.isSurfaceActive()) {
                t.toggleSurfaceMode();
            }
            Harness.command("tp @s " + (Arena.cx + 6.5) + " " + (Arena.FLOOR + 2.5) + " " + (Arena.cz - 5.5));
        });
        s.waitTicks(40);
        s.run("water walk result", () -> {
            double y = H.p().getY();
            s.check("Water Walk (solid) keeps the player on the water surface",
                    TravelBootstrap.terrain().isSurfaceActive() && y >= Arena.FLOOR + 0.85 && !H.p().isInWater(),
                    Harness.fmt("y=%.3f (water top at %d+1), inWater=%s, onGround=%s, block below %s", y, Arena.FLOOR,
                            H.p().isInWater(), H.p().onGround(), H.blockId(H.floorX(), Arena.FLOOR, H.floorZ())));
        });
        s.always("cleanup", () -> {
            if (lease[0] != null) {
                lease[0].close();
            }
            if (TravelBootstrap.bridge().isRunning()) {
                TravelBootstrap.bridge().toggle();
            }
            if (TravelBootstrap.terrain().isSurfaceActive()) {
                TravelBootstrap.terrain().toggleSurfaceMode();
            }
        });
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        return s;
    }
}
