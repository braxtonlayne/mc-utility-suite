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
        // head steadiness: once the camera first faces back along the bridge (west, yaw 90), how far it wanders
        float[] look = {Float.NaN, Float.NaN, 0f, 0f, 0f, 999f, -999f}; // last yaw, last pitch, yaw dev max, travel, max step, pitch min, pitch max
        boolean[] settled = new boolean[1];
        s.waitUpTo("bridge stops", 20 * 40, () -> {
            float yaw = H.p().getYRot();
            float pitch = H.p().getXRot();
            float dev = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - 90f));
            if (!settled[0] && dev < 3f) {
                settled[0] = true;
            } else if (settled[0]) {
                float step = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - look[0])) + Math.abs(pitch - look[1]);
                look[2] = Math.max(look[2], dev);
                look[3] += step;
                look[4] = Math.max(look[4], step);
                look[5] = Math.min(look[5], pitch);
                look[6] = Math.max(look[6], pitch);
            }
            look[0] = yaw;
            look[1] = pitch;
            return !TravelBootstrap.bridge().isRunning();
        });
        s.run("bridge result", () -> {
            s.check("bridge look is one steady pose (no per-block head swings)", settled[0] && look[2] <= 8f
                            && look[4] <= 10f && look[6] - look[5] <= 20f,
                    Harness.fmt("settled=%s, yaw off the bridge line max %.1f deg, head travel %.1f deg, largest tick"
                                    + " step %.1f deg, pitch %.1f..%.1f", settled[0], look[2], look[3], look[4], look[5],
                            look[6]));
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
        Shots.add(s, "travel-bridge");
        walkBridge(s, lease, "INSTANT", "BRIDGER", "AT_EDGE", true, 4, "walk-instant");
        walkBridge(s, lease, "LEGIT", "REAL", "OFF", false, 8, "walk-legit");
        parkourJump(s, lease);
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
        Shots.add(s, "travel-waterwalk");
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

    /**
     * Bridge Assist WALK mode: the "user" (a playtest movement lease) walks or sprints east off the platform edge with
     * the bridge on; blocks must appear under them and they must not fall. In single-player the integrated server's
     * copy of the player shows what the server saw: crouching while the client was not, and the bridger look (facing
     * back west) while the camera faced east.
     */
    private static void walkBridge(Scenario s, ControlLease[] lease, String pace, String look, String sneak,
                                   boolean sprint, int laneDz, String shot) {
        int[] start = new int[1];
        boolean[] sawServerSneak = new boolean[1];
        boolean[] sawClientSneak = new boolean[1];
        float[] maxBackLook = new float[1];
        s.cmd("clear @s");
        s.cmd("give @s minecraft:cobblestone 64");
        s.cmd(() -> Arena.tp(10.5, laneDz + 0.5, -90f));
        s.waitTicks(10);
        s.run("walk bridge " + pace + " on", () -> {
            H.setting(BridgeAssist.ID, "mode", "WALK");
            H.setting(BridgeAssist.ID, "walk_pace", pace);
            H.setting(BridgeAssist.ID, "server_look", look);
            H.setting(BridgeAssist.ID, "server_sneak", sneak);
            H.setting(BridgeAssist.ID, "hide_sprint", sprint);
            H.setting(BridgeAssist.ID, "budget", 0);
            H.setting(BridgeAssist.ID, "hotbar_reserve", 0);
            H.enable(BridgeAssist.ID, true);
            start[0] = H.count("minecraft:cobblestone");
            boolean started = TravelBootstrap.bridge().start();
            s.check("walk bridge (" + pace + ", look " + look + ", sneak " + sneak + ") starts", started,
                    "running=" + TravelBootstrap.bridge().isRunning());
            lease[0] = Services.arbiter().request(LeaseRequest.of("playtest_mover", ControlPriority.TRAVEL, null,
                    ControlChannel.MOVEMENT)).orElseThrow();
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, true);
            if (sprint) {
                Services.input().setKeyHeld(lease[0], SimKey.SPRINT, true);
            }
        });
        s.waitUpTo("walk east to x+20 (" + pace + ")", 20 * 15, () -> {
            var sp = serverPlayer();
            if (sp != null) {
                sawServerSneak[0] |= sp.isShiftKeyDown();
                // bridger look: the server yaw points back west (+90) while the camera faces east (-90)
                float back = (float) Math.abs(com.utilitysuite.travel.Angles.delta(H.p().getYRot(), sp.getYRot()));
                maxBackLook[0] = Math.max(maxBackLook[0], back);
            }
            sawClientSneak[0] |= H.p().isShiftKeyDown();
            return H.p().getX() >= Arena.cx + 20.5 || H.p().getY() < Arena.FLOOR + 0.5;
        });
        s.run("walk bridge " + pace + " result", () -> {
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, false);
            Services.input().setKeyHeld(lease[0], SimKey.SPRINT, false);
            lease[0].close();
            int deck = 0;
            for (int dx = 13; dx <= 20; dx++) {
                if (!H.block(Arena.cx + dx, Arena.FLOOR, Arena.cz + laneDz).isAir()) {
                    deck++;
                }
            }
            double y = H.p().getY();
            s.check("walk bridge (" + pace + ") keeps the player on a deck it builds under them",
                    deck >= 7 && y >= Arena.FLOOR + 0.99,
                    "deck blocks x+13..x+20: " + deck + ", used " + (start[0] - H.count("minecraft:cobblestone"))
                            + ", player " + H.posStr());
            if (!"OFF".equals(sneak)) {
                s.check("server saw a crouch while the client never sneaked",
                        sawServerSneak[0] && !sawClientSneak[0],
                        "server sneak seen=" + sawServerSneak[0] + ", client sneak seen=" + sawClientSneak[0]);
            }
            if ("BRIDGER".equals(look)) {
                s.check("server saw the backward bridger look while the camera faced forward", maxBackLook[0] > 135f,
                        Harness.fmt("max |server yaw - camera yaw| = %.1f deg", maxBackLook[0]));
            }
        });
        Shots.add(s, "travel-" + shot);
        s.run("walk bridge " + pace + " off", () -> {
            if (TravelBootstrap.bridge().isRunning()) {
                TravelBootstrap.bridge().toggle();
            }
            H.setting(BridgeAssist.ID, "mode", "LINE");
        });
    }

    /**
     * Movement Assist: the "user" (a movement lease) sprints west off the arena edge toward a 3-air-block gap and never
     * presses jump. The assist must time the edge jump itself and land on the far platform; the landing overlay must be
     * up while airborne (screenshot).
     */
    private static void parkourJump(Scenario s, ControlLease[] lease) {
        var ma = TravelBootstrap.movement();
        int[] jumpsBefore = new int[1];
        boolean[] sawLanding = new boolean[1];
        boolean[] shot = new boolean[1];
        double[] minY = {Double.MAX_VALUE};
        // gap x-13..x-15 (three air blocks), landing platform x-16..x-22, lane z-2..z+2
        s.cmd(() -> Arena.fill(-22, 1, -2, -13, 4, 2, "air"));
        s.cmd(() -> Arena.fill(-15, -4, -2, -13, 0, 2, "air"));
        s.cmd(() -> Arena.fill(-22, 0, -2, -16, 0, 2, "stone"));
        s.cmd(() -> Arena.tp(-3.5, 0.5, 90f));
        s.waitTicks(10);
        s.run("movement assist on", () -> {
            H.setting(com.utilitysuite.travel.movement.MovementAssist.ID, "edge_jump", true);
            H.setting(com.utilitysuite.travel.movement.MovementAssist.ID, "air_steer", true);
            H.setting(com.utilitysuite.travel.movement.MovementAssist.ID, "overlay", true);
            H.setting(com.utilitysuite.travel.movement.MovementAssist.ID, "assist_automation", true);
            H.enable(com.utilitysuite.travel.movement.MovementAssist.ID, true);
            jumpsBefore[0] = ma.edgeJumps();
            lease[0] = Services.arbiter().request(LeaseRequest.of("playtest_mover", ControlPriority.TRAVEL, null,
                    ControlChannel.MOVEMENT)).orElseThrow();
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, true);
            Services.input().setKeyHeld(lease[0], SimKey.SPRINT, true);
        });
        s.waitUpTo("sprint west over the gap", 20 * 8, () -> {
            minY[0] = Math.min(minY[0], H.p().getY());
            if (!H.p().onGround()) {
                sawLanding[0] |= ma.showingLanding();
                if (!shot[0] && H.p().getX() < Arena.cx - 13.5) {
                    shot[0] = true;
                    Shots.take(s, "movement-parkour-airborne");
                }
            }
            return (H.p().onGround() && H.p().getX() < Arena.cx - 16.5) || H.p().getY() < Arena.FLOOR - 2;
        });
        s.run("parkour result", () -> {
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, false);
            Services.input().setKeyHeld(lease[0], SimKey.SPRINT, false);
            lease[0].close();
            double x = H.p().getX() - Arena.cx;
            s.check("movement assist times the edge jump and clears a 3-block gap",
                    ma.edgeJumps() > jumpsBefore[0] && H.p().getY() >= Arena.FLOOR + 0.99 && x < -15.7,
                    Harness.fmt("edge jumps %d -> %d, player x=cx%+.2f y=%.2f (min y %.2f), corrections %d",
                            jumpsBefore[0], ma.edgeJumps(), x, H.p().getY(), minY[0], ma.corrections()));
            s.check("landing overlay shown while airborne", sawLanding[0], "seen=" + sawLanding[0]);
        });
        Shots.add(s, "movement-parkour-landed");
        s.run("movement assist off", () -> {
            H.setting(com.utilitysuite.travel.movement.MovementAssist.ID, "assist_automation", false);
            H.enable(com.utilitysuite.travel.movement.MovementAssist.ID, false);
        });
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
    }

    /** The integrated server's copy of the local player (single-player only), or null. */
    private static net.minecraft.server.level.ServerPlayer serverPlayer() {
        var server = H.mc().getSingleplayerServer();
        return server == null ? null : server.getPlayerList().getPlayer(H.p().getUUID());
    }
}
