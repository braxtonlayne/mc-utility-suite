package com.utilitysuite.devtest;

import com.utilitysuite.core.api.ITravel;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.ServerTickFeed;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.jobs.fieldwork.AfkGrinderModule;
import com.utilitysuite.jobs.fieldwork.DebrisBlastCycle;
import com.utilitysuite.jobs.fieldwork.DebrisBlastModule;
import com.utilitysuite.jobs.fieldwork.LavaPlugModule;
import com.utilitysuite.jobs.fieldwork.PenSweepModule;
import com.utilitysuite.jobs.fieldwork.VeinWorkModule;
import com.utilitysuite.jobs.schematic.HighwayBuilder;
import com.utilitysuite.jobs.schematic.SchematicBootstrap;
import com.utilitysuite.jobs.schematic.SchematicToolkit;
import com.utilitysuite.jobs.schematic.SymmetryBuilder;
import com.utilitysuite.jobs.schematic.gen.ShapeVoxelizer;
import com.utilitysuite.jobs.schematic.gen.SymmetryTransform;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.tools.player.PiglinGuard;
import com.utilitysuite.travel.autopilot.TravelAutopilot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Scenario: the FIELD, BUILD and NETHER jobs that had only unit tests, exercised in game. Highway builder (full-width
 * tunnel dig, ice road boat hand-off), pen sweep, AFK grinder (cooldown + server-freeze pause), symmetry builder and the
 * shape generator into Build Assist, Riptide launch, then in the Nether: debris blasting (no-charge wait, then a full
 * bed cycle with the Lava Plug / Vein Work hand-offs) and piglin bartering (hand-over and loot pick-up). The harness
 * plays the user where the features expect one (walking the debris tunnel, stepping back behind the shield).
 * Select it alone with {@code -PplaytestOnly=FieldJobs}.
 */
final class FieldJobsScenario {

    private static final String[] MODULES = {HighwayBuilder.ID, TravelAutopilot.ID, PenSweepModule.ID,
            AfkGrinderModule.ID, SymmetryBuilder.ID, DebrisBlastModule.ID, VeinWorkModule.ID, LavaPlugModule.ID,
            PiglinGuard.ID};
    private static final String NETHER = "execute in minecraft:the_nether run ";
    /** Nether test area (fixed coordinates, all built with /fill). */
    private static final int NX = 400, NY = 15, NZ = 400;
    private static final String WAYPOINT = "pt_highway_end";

    private FieldJobsScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("14-fieldjobs", "Field, build and Nether jobs in game: highway, pen, grinder,"
                + " symmetry, shapes, riptide, debris blasting, piglin barter");
        Arena.ensure(s);
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("clear @s");
        s.cmd("effect give @s minecraft:resistance 600 4 true");
        s.cmd("effect give @s minecraft:fire_resistance 600 0 true");
        s.cmd("effect give @s minecraft:saturation 600 0 true");
        highwayTunnel(s);
        highwayIce(s);
        penSweep(s);
        afkGrinder(s);
        symmetry(s);
        shapes(s);
        riptide(s);
        debris(s);
        piglin(s);
        s.always("disarm", () -> {
            if (H.mc().gui.screen() != null) {
                H.mc().gui.setScreen(null);
            }
            for (String id : MODULES) {
                H.enable(id, false);
            }
            H.setting(TravelAutopilot.ID, "elytra_riptide_launch", false);
            H.setting(DebrisBlastModule.ID, "relaxed", false);
            SchematicToolkit tk = SchematicBootstrap.toolkit();
            if (tk != null) {
                tk.cancel();
            }
            IWaypointService wp = Services.waypointsOrNull();
            if (wp != null) {
                wp.byName(WAYPOINT).ifPresent(w -> wp.remove(w.id()));
            }
            Harness.command("tick unfreeze");
            Harness.command("weather clear");
            Harness.command("ride @s dismount");
            Harness.command("kill @e[type=!minecraft:player]");
            Harness.command("clear @s");
            Harness.command("effect clear @s");
            Harness.command("execute in minecraft:overworld run tp @s " + Arena.cx + ".5 " + (Arena.FLOOR + 1) + " "
                    + Arena.cz + ".5 0 0");
            Harness.command(Arena.fill(-12, 1, -12, 12, 9, 12, "air"));
            Harness.command(Arena.fill(-12, 0, -12, 12, 0, 12, "stone"));
            Harness.command(Arena.fill(4, 0, -8, 8, 0, -4, "water"));
        });
        s.waitTicks(20);
        return s;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static boolean on(String id) {
        return Services.modules().byId(id).map(m -> m.isEnabled()).orElse(false);
    }

    private static Object field(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** A private field declared on {@code o}'s class or any superclass (diagnostics), or null. */
    private static Object deepField(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** Field-module hands, look and crosshair (diagnostics). */
    private static String handsDiag(Object module) {
        Object hands = deepField(module, "hands");
        var hit = H.mc().hitResult;
        String hr = hit instanceof net.minecraft.world.phys.BlockHitResult bh
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? "hit " + bh.getBlockPos().toShortString() : "hit " + (hit == null ? "null" : hit.getType());
        return "status '" + deepField(module, "status") + "', hands " + (hands == null ? "?" : H.fields(hands,
                "actionCell", "aimSet", "ticks", "cooldown", "lease")) + ", yaw " + Harness.fmt("%.1f", H.p().getYRot())
                + " pitch " + Harness.fmt("%.1f", H.p().getXRot()) + ", " + hr + ", attack down "
                + H.mc().options.keyAttack.isDown() + ", mouse grabbed " + H.mc().mouseHandler.isMouseGrabbed() + ", player " + H.posStr() + ", " + H.arbiterState();
    }

    private static int intField(Object o, String name) {
        Object v = field(o, name);
        return v instanceof Integer i ? i : Integer.MIN_VALUE;
    }

    private static int ax(int dx) {
        return Arena.cx + dx;
    }

    private static int ay(int dy) {
        return Arena.FLOOR + dy;
    }

    private static int az(int dz) {
        return Arena.cz + dz;
    }

    private static ServerLevel serverLevel(net.minecraft.resources.ResourceKey<Level> key) {
        var server = H.mc().getSingleplayerServer();
        return server == null ? null : server.getLevel(key);
    }

    private static Entity serverEntity(net.minecraft.resources.ResourceKey<Level> key, Entity client) {
        ServerLevel l = serverLevel(key);
        return l == null || client == null ? null : l.getEntity(client.getUUID());
    }

    private static void lookAt(double x, double y, double z) {
        var p = H.p();
        double dx = x - p.getX();
        double dy = y - p.getEyeY();
        double dz = z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    // ---- 1. highway, tunnel mode, full width ----------------------------------------------------------------------

    /** Solid stone block x-9..10, y1..4, z6..10; the player starts west of it at x-10, the target waypoint at x+10. */
    private static void highwayTunnel(Scenario s) {
        s.cmd(() -> Arena.fill(-12, 1, 5, 12, 6, 11, "air"));
        s.cmd(() -> Arena.fill(-9, 1, 6, 10, 4, 10, "stone"));
        s.cmd("clear @s");
        s.cmd("give @s minecraft:diamond_pickaxe[enchantments={levels:{\"minecraft:efficiency\":5}}]");
        s.cmd("give @s minecraft:netherrack 64");
        s.cmd("effect give @s minecraft:haste 300 1 true");
        s.cmd(() -> Arena.tp(-9.5, 8.5, -90f));
        s.waitTicks(10);
        int[] dug0 = new int[1];
        s.run("highway (tunnel) on", () -> {
            IWaypointService wp = Services.waypoints();
            wp.byName(WAYPOINT).ifPresent(w -> wp.remove(w.id()));
            wp.add(WAYPOINT, "Playtest", ax(10), ay(1), az(8), H.mc().level.dimension().identifier().toString());
            H.setting(HighwayBuilder.ID, "target", WAYPOINT);
            H.setting(HighwayBuilder.ID, "mode", "TUNNEL");
            H.setting(HighwayBuilder.ID, "width", 3);
            H.setting(HighwayBuilder.ID, "dig_tunnel", true);
            H.setting(HighwayBuilder.ID, "dig_sides", true);
            H.setting(HighwayBuilder.ID, "boat_handoff", false);
            dug0[0] = tunnelAir();
            H.enable(HighwayBuilder.ID, true);
        });
        String[] lastStatus = {""};
        s.waitUpTo("highway (tunnel) digs to the end", 20 * 180, () -> {
            HighwayBuilder m = H.module(HighwayBuilder.ID);
            if (!m.status().equals(lastStatus[0])) {
                lastStatus[0] = m.status();
                s.note("highway status: " + lastStatus[0] + " at " + H.posStr() + ", tunnel air " + tunnelAir()
                        + "/" + TUNNEL_CELLS);
            }
            return !m.isEnabled();
        });
        Shots.add(s, "fieldjobs-highway-tunnel");
        s.run("highway (tunnel) result", () -> {
            HighwayBuilder m = H.module(HighwayBuilder.ID);
            int air = tunnelAir();
            StringBuilder missing = new StringBuilder();
            for (int dx = -9; dx <= 10 && missing.length() < 200; dx++) {
                for (int dz = 7; dz <= 9; dz++) {
                    for (int dy = 1; dy <= 3; dy++) {
                        if (!H.block(ax(dx), ay(dy), az(dz)).isAir()) {
                            missing.append(dx).append(',').append(dy).append(',').append(dz).append(' ');
                        }
                    }
                }
            }
            s.check("highway tunnel: every cell of the 3-wide, 3-high tunnel is dug", air == TUNNEL_CELLS,
                    air + "/" + TUNNEL_CELLS + " cells air (" + dug0[0] + " before); still solid: " + missing
                            + "; player " + H.posStr());
            s.check("highway tunnel: the run finishes at the target", !m.isEnabled()
                            && m.status().equals("Highway finished"),
                    "enabled " + m.isEnabled() + ", status '" + m.status() + "', player " + H.posStr() + "; "
                            + H.fields(m, "digCell", "stepTarget", "navFailures") + "; " + H.arbiterState());
            H.enable(HighwayBuilder.ID, false);
        });
        s.run("highway (tunnel) cleanup", () -> {
            SchematicToolkit tk = SchematicBootstrap.toolkit();
            if (tk != null) {
                tk.cancel();
            }
            Harness.command(Arena.fill(-12, 1, 5, 12, 6, 11, "air"));
        });
    }

    private static final int TUNNEL_CELLS = 20 * 3 * 3;

    /** Air cells of the tunnel x-9..10, y1..3, z7..9 (the part inside the stone block). */
    private static int tunnelAir() {
        int n = 0;
        for (int dx = -9; dx <= 10; dx++) {
            for (int dz = 7; dz <= 9; dz++) {
                for (int dy = 1; dy <= 3; dy++) {
                    if (H.block(ax(dx), ay(dy), az(dz)).isAir()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    // ---- 2. highway, ice mode + boat hand-off ---------------------------------------------------------------------

    private static void highwayIce(Scenario s) {
        s.cmd(() -> Arena.fill(-12, 0, -1, 12, 0, 1, "minecraft:packed_ice"));
        s.cmd(() -> "summon minecraft:boat " + (ax(-10) + 0.5) + " " + ay(1) + " " + (az(0) + 0.5));
        s.cmd(() -> Arena.tp(-9.5, 0.5, -90f));
        s.cmd("ride @s mount @e[type=minecraft:boat,limit=1,sort=nearest]");
        s.waitUpTo("in the boat", 40, () -> H.p().getVehicle() != null);
        boolean[] sawBoat = new boolean[1];
        double[] startX = new double[1];
        String[] seen = {""};
        s.run("highway (ice) on", () -> {
            IWaypointService wp = Services.waypoints();
            wp.byName(WAYPOINT).ifPresent(w -> wp.remove(w.id()));
            wp.add(WAYPOINT, "Playtest", ax(11), ay(1), az(0), H.mc().level.dimension().identifier().toString());
            H.setting(HighwayBuilder.ID, "mode", "ICE");
            H.setting(HighwayBuilder.ID, "width", 2);
            H.setting(HighwayBuilder.ID, "boat_handoff", true);
            H.setting(HighwayBuilder.ID, "target", WAYPOINT);
            H.enable(TravelAutopilot.ID, true);
            startX[0] = H.p().getX();
            H.enable(HighwayBuilder.ID, true);
        });
        s.waitUpTo("boat route starts", 20 * 10, () -> {
            TravelAutopilot ap = H.module(TravelAutopilot.ID);
            HighwayBuilder m = H.module(HighwayBuilder.ID);
            if (!m.status().equals(seen[0])) {
                seen[0] = m.status();
                s.note("highway (ice) status: " + seen[0]);
            }
            sawBoat[0] |= "BOAT".equals(ap.runName());
            return sawBoat[0] && H.p().getX() > startX[0] + 2;
        });
        s.run("highway (ice) result", () -> {
            TravelAutopilot ap = H.module(TravelAutopilot.ID);
            HighwayBuilder m = H.module(HighwayBuilder.ID);
            boolean riding = H.p().getVehicle() != null;
            s.check("highway ice road: boat hand-off starts a BOAT route toward the road end", sawBoat[0] && riding
                            && H.p().getX() > startX[0] + 2,
                    "BOAT run seen " + sawBoat[0] + ", now " + ap.runName() + ", riding " + riding + ", moved "
                            + Harness.fmt("%.2f", H.p().getX() - startX[0]) + " east; highway status '" + m.status()
                            + "'; " + H.arbiterState());
            H.enable(HighwayBuilder.ID, false);
            ap.stop();
            H.enable(TravelAutopilot.ID, false);
            SchematicToolkit tk = SchematicBootstrap.toolkit();
            if (tk != null) {
                tk.cancel();
            }
        });
        s.cmd("ride @s dismount");
        s.cmd("kill @e[type=minecraft:boat]");
        s.cmd(() -> Arena.fill(-12, 0, -1, 12, 0, 1, "stone"));
        s.cmd(() -> Arena.fill(-12, 1, -2, 12, 3, 2, "air"));
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
    }

    // ---- 3. pen sweep ---------------------------------------------------------------------------------------------

    private static void penSweep(Scenario s) {
        // pen: fence ring x-11..-5, z5..11 around the player at (-8, 8); animals within 2 blocks (reach 3)
        s.cmd(() -> Arena.fill(-11, 1, 5, -5, 1, 11, "minecraft:oak_fence"));
        s.cmd(() -> Arena.fill(-10, 1, 6, -6, 2, 10, "air"));
        s.cmd("clear @s");
        s.cmd("give @s minecraft:shears");
        s.cmd("give @s minecraft:bucket 2");
        s.cmd("give @s minecraft:wheat 8");
        s.cmd(() -> Arena.tp(-7.5, 8.5, 0f));
        s.cmd(() -> "summon minecraft:sheep " + (ax(-9) + 0.5) + " " + ay(1) + " " + (az(8) + 0.5)
                + " {NoAI:1b,PersistenceRequired:1b,Color:0b}");
        s.cmd(() -> "summon minecraft:cow " + (ax(-8) + 0.5) + " " + ay(1) + " " + (az(10) + 0.5)
                + " {NoAI:1b,PersistenceRequired:1b}");
        s.cmd(() -> "summon minecraft:cow " + (ax(-6) + 0.5) + " " + ay(1) + " " + (az(10) + 0.5)
                + " {NoAI:1b,PersistenceRequired:1b}");
        s.waitTicks(10);
        int[] wheat0 = new int[1];
        s.run("pen sweep on", () -> {
            H.setting(PenSweepModule.ID, "radius", 3);
            wheat0[0] = H.count("minecraft:wheat");
            H.enable(PenSweepModule.ID, true);
        });
        s.waitUpTo("pen sweep works", 20 * 40, () -> sheared() && H.count("minecraft:milk_bucket") > 0
                && H.count("minecraft:wheat") <= wheat0[0] - 2);
        Shots.add(s, "fieldjobs-pen");
        s.run("pen sweep result", () -> {
            int wheat = H.count("minecraft:wheat");
            int inLove = 0;
            for (Cow c : H.mc().level.getEntitiesOfClass(Cow.class, H.p().getBoundingBox().inflate(6))) {
                Entity se = serverEntity(Level.OVERWORLD, c);
                if (se instanceof Cow sc && sc.isInLove()) {
                    inLove++;
                }
            }
            String diag = H.fields(H.module(PenSweepModule.ID), "count", "target", "action", "penX", "penZ")
                    + "; " + H.arbiterState();
            s.check("pen sweep: shears the sheep", sheared(), "sheared " + sheared() + "; " + diag);
            s.check("pen sweep: milks a cow into an empty bucket", H.count("minecraft:milk_bucket") > 0,
                    "milk buckets " + H.count("minecraft:milk_bucket") + ", buckets " + H.count("minecraft:bucket"));
            s.check("pen sweep: feeds both cows to breed", wheat <= wheat0[0] - 2 && inLove + (wheat0[0] - wheat) >= 2,
                    "wheat " + wheat0[0] + " -> " + wheat + ", cows in love (server) " + inLove + "; module on "
                            + on(PenSweepModule.ID));
            H.enable(PenSweepModule.ID, false);
        });
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd(() -> Arena.fill(-11, 1, 5, -5, 2, 11, "air"));
    }

    private static boolean sheared() {
        for (Sheep sh : H.mc().level.getEntitiesOfClass(Sheep.class, H.p().getBoundingBox().inflate(6))) {
            if (sh.isSheared()) {
                return true;
            }
        }
        return false;
    }

    // ---- 4. AFK grinder -------------------------------------------------------------------------------------------

    private static void afkGrinder(Scenario s) {
        s.cmd("clear @s");
        s.cmd("item replace entity @s hotbar.0 with minecraft:iron_sword");
        s.cmd(() -> Arena.tp(0.5, -9.5, 0f));
        s.cmd(() -> "summon minecraft:husk " + (ax(0) + 0.5) + " " + ay(1) + " " + (az(-7) + 0.5)
                + " {NoAI:1b,PersistenceRequired:1b,Health:500f,attributes:[{id:\"minecraft:generic.max_health\","
                + "base:500d},{id:\"minecraft:generic.knockback_resistance\",base:1d}]}");
        s.waitTicks(10);
        List<int[]> swings = new ArrayList<>(); // tick, charge*100
        int[] clock = new int[1];
        float[] prev = {1f};
        int[] prevSlot = {-1};
        float[] health0 = new float[1];
        s.run("grinder on", () -> {
            H.setting(AfkGrinderModule.ID, "reach", 3.0);
            var husk = husk();
            health0[0] = husk == null ? -1 : husk.getHealth();
            H.enable(AfkGrinderModule.ID, true);
        });
        // swing watch: the attack-strength meter drops when a swing lands
        StringBuilder trace = new StringBuilder();
        Scenario.Step watch = t -> {
            clock[0]++;
            float now = H.p().getAttackStrengthScale(0f);
            if (clock[0] <= 60) {
                trace.append(Math.round(now * 100)).append(H.p().getInventory().getSelectedSlot() == 0 ? "" : "s"
                        + H.p().getInventory().getSelectedSlot()).append(H.mc().options.keyAttack.isDown() ? "A" : "").append(' ');
            } else if (clock[0] == 61) {
                s.note("grinder strength trace (%, sN = other slot, A = attack key down): " + trace);
            }
            int slotNow = H.p().getInventory().getSelectedSlot();
            boolean swapped = prevSlot[0] >= 0 && slotNow != prevSlot[0]; // a hotbar swap also resets the meter
            prevSlot[0] = slotNow;
            if (now < prev[0] - 0.4f && !swapped) {
                swings.add(new int[] {clock[0], Math.round(prev[0] * 100)});
            }
            prev[0] = now;
            return t >= 20 * 8;
        };
        s.step("grinder swings for 8 s", watch);
        s.run("grinder cooldown result", () -> {
            int n = swings.size();
            int minGap = Integer.MAX_VALUE;
            int minCharge = 100;
            for (int i = 0; i < n; i++) {
                minCharge = Math.min(minCharge, swings.get(i)[1]);
                if (i > 0) {
                    minGap = Math.min(minGap, swings.get(i)[0] - swings.get(i - 1)[0]);
                }
            }
            var husk = husk();
            float hp = husk == null ? -1 : husk.getHealth();
            // iron sword: the meter refills in about 10 watch steps (trace: +9.6% a step); a fist would refill in 5. The
            // charge read just before each drop is the real test, the gap only rules out fist-speed swings
            s.check("AFK grinder: hits the mob only at full charge", n >= 4 && minCharge >= 90 && minGap >= 9
                            && hp < health0[0],
                    n + " swings in 8 s, lowest charge at swing " + minCharge + "%, shortest gap " + minGap
                            + " ticks, husk health " + health0[0] + " -> " + hp + "; status '"
                            + ((AfkGrinderModule) H.module(AfkGrinderModule.ID)).status() + "'");
            swings.clear();
        });
        s.cmd("tick freeze");
        s.waitTicks(40); // the heartbeat goes overdue (period + 400 ms)
        boolean[] heldStatus = new boolean[1];
        s.step("grinder while the server is frozen", t -> {
            AfkGrinderModule m = H.module(AfkGrinderModule.ID);
            heldStatus[0] |= m.status().startsWith("Server stalled");
            return watch.tick(t) || t >= 20 * 4;
        });
        s.run("grinder freeze result", () -> {
            AfkGrinderModule m = H.module(AfkGrinderModule.ID);
            s.check("AFK grinder: no swings while the server is frozen (/tick freeze)", swings.isEmpty()
                            && heldStatus[0],
                    swings.size() + " swings in 4 s frozen, 'Server stalled' status seen " + heldStatus[0]
                            + ", heartbeat overdue " + ServerTickFeed.overdueMs(System.currentTimeMillis())
                            + " ms, status '" + m.status() + "', serverHealth pause "
                            + Services.serverHealth().pauseHighRisk());
            swings.clear();
        });
        s.cmd("tick unfreeze");
        s.step("grinder after unfreeze", t -> watch.tick(t) || t >= 20 * 4);
        s.run("grinder resume result", () -> {
            s.check("AFK grinder: swings resume after /tick unfreeze", !swings.isEmpty(),
                    swings.size() + " swings in 4 s after unfreeze, status '"
                            + ((AfkGrinderModule) H.module(AfkGrinderModule.ID)).status() + "'");
            H.enable(AfkGrinderModule.ID, false);
        });
        s.cmd("kill @e[type=minecraft:husk]");
    }

    private static net.minecraft.world.entity.monster.zombie.Husk husk() {
        var list = H.mc().level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Husk.class,
                H.p().getBoundingBox().inflate(8));
        return list.isEmpty() ? null : list.get(0);
    }

    // ---- 5. symmetry builder -> Build Assist ----------------------------------------------------------------------

    private static void symmetry(Scenario s) {
        s.cmd("clear @s");
        s.cmd("give @s minecraft:cobblestone 32");
        s.cmd(() -> Arena.tp(0.5, -10.5, 0f));
        s.waitTicks(5);
        int[] centre = new int[3];
        int[] mirror = new int[3];
        s.run("symmetry on", () -> {
            lookAt(ax(0) + 0.5, ay(0) + 0.5, az(-10) + 0.5); // straight down: centre = the block under the feet
            H.setting(SymmetryBuilder.ID, "mode", "MIRROR_X");
            H.setting(SymmetryBuilder.ID, "radius", 8);
            H.enable(SymmetryBuilder.ID, true);
        });
        s.waitTicks(3);
        s.run("place the original block", () -> {
            SymmetryBuilder m = H.module(SymmetryBuilder.ID);
            long c = m.centre();
            centre[0] = BlockPos.getX(c);
            centre[1] = BlockPos.getY(c);
            centre[2] = BlockPos.getZ(c);
            int dx = 2, dz = 1;
            int img = SymmetryTransform.Mode.MIRROR_X.images()[0];
            mirror[0] = centre[0] + SymmetryTransform.tx(img, dx, dz);
            mirror[1] = ay(1);
            mirror[2] = centre[2] + SymmetryTransform.tz(img, dx, dz);
            Harness.command("setblock " + (centre[0] + dx) + " " + ay(1) + " " + (centre[2] + dz)
                    + " minecraft:cobblestone");
            s.note("symmetry centre " + centre[0] + "," + centre[1] + "," + centre[2] + " (arena "
                    + Arena.cx + "," + Arena.FLOOR + "," + Arena.cz + "); original at +" + dx + ",+" + dz
                    + ", mirror expected at " + mirror[0] + "," + mirror[1] + "," + mirror[2]);
        });
        s.waitUpTo("mirror placed", 20 * 30, () -> H.blockId(mirror[0], mirror[1], mirror[2])
                .equals("minecraft:cobblestone"));
        Shots.add(s, "fieldjobs-symmetry");
        s.run("symmetry result", () -> {
            SymmetryBuilder m = H.module(SymmetryBuilder.ID);
            SchematicToolkit tk = SchematicBootstrap.toolkit();
            String card = tk.jobCard().map(c -> c.name() + " " + c.phase() + " " + c.placedBlocks() + "/"
                    + c.totalBlocks() + " notes " + c.notes()).orElse("no job");
            s.check("symmetry builder: mirrored counterpart is queued and placed by Build Assist",
                    m.layerSize() >= 1 && H.blockId(mirror[0], mirror[1], mirror[2]).equals("minecraft:cobblestone"),
                    "layer " + m.layerSize() + ", status '" + m.status() + "', mirror cell "
                            + H.blockId(mirror[0], mirror[1], mirror[2]) + ", job " + card + "; " + H.arbiterState());
            H.enable(SymmetryBuilder.ID, false);
            tk.cancel();
        });
        s.cmd(() -> Arena.fill(-8, 1, -12, 8, 3, -9, "air"));
    }

    // ---- 6. shape generator -> Build Assist -----------------------------------------------------------------------

    private static void shapes(Scenario s) {
        s.cmd("clear @s");
        s.cmd("give @s minecraft:cobblestone 64");
        s.cmd("give @s minecraft:dirt 16"); // Build Assist scaffolding under the dome overhang
        s.cmd(() -> Arena.tp(-4.5, -10.5, 0f));
        s.waitTicks(5);
        SchematicVolume[] vol = new SchematicVolume[1];
        String[] err = {null};
        long[] anchor = new long[1];
        s.run("generate a dome", () -> {
            SchematicToolkit tk = SchematicBootstrap.toolkit();
            anchor[0] = BlockPos.asLong(ax(-8), ay(1), az(-10));
            ShapeVoxelizer.Params params = ShapeVoxelizer.Params.of(ShapeVoxelizer.Kind.DOME, 2, 2, 1,
                    "minecraft:cobblestone");
            tk.startGenerated("pt_dome", () -> ShapeVoxelizer.voxelize(params), anchor[0], false, (v, e) -> {
                vol[0] = v;
                err[0] = e;
            });
        });
        s.waitUpTo("dome generated", 20 * 10, () -> vol[0] != null || err[0] != null);
        int[] last = {-1};
        s.waitUpTo("dome built", 20 * 120, () -> {
            var c = SchematicBootstrap.toolkit().jobCard();
            int placed = c.map(x -> x.placedBlocks() * 64 + (x.phase().hashCode() & 63)).orElse(-1);
            if (placed != last[0]) {
                last[0] = placed;
                s.note("dome: " + c.map(x -> x.phase() + " " + x.placedBlocks() + "/" + x.totalBlocks()
                        + " notes " + x.notes()).orElse("no job"));
            }
            return c.map(x -> x.totalBlocks() > 0 && x.placedBlocks() >= x.totalBlocks()).orElse(false);
        });
        Shots.add(s, "fieldjobs-dome");
        s.run("dome result", () -> {
            SchematicToolkit tk = SchematicBootstrap.toolkit();
            var c = tk.jobCard();
            int cobble = 0;
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    for (int dy = -1; dy <= 4; dy++) {
                        if (H.block(BlockPos.getX(anchor[0]) + dx, BlockPos.getY(anchor[0]) + dy,
                                BlockPos.getZ(anchor[0]) + dz).is(Blocks.COBBLESTONE)) {
                            cobble++;
                        }
                    }
                }
            }
            int blocks = vol[0] == null ? -1 : vol[0].blockCount();
            s.check("shape generator: a small dome is generated and placed by Build Assist",
                    vol[0] != null && blocks > 0 && cobble >= blocks,
                    "volume " + (vol[0] == null ? "none (" + err[0] + ")" : vol[0].sizeX() + "x" + vol[0].sizeY()
                            + "x" + vol[0].sizeZ() + ", " + blocks + " blocks") + "; cobblestone in the world "
                            + cobble + "; card " + c.map(x -> x.phase() + " " + x.placedBlocks() + "/"
                            + x.totalBlocks() + " notes " + x.notes()).orElse("no job") + "; " + H.arbiterState());
            try {
                java.lang.reflect.Field f = SchematicToolkit.class.getDeclaredField("assist");
                f.setAccessible(true);
                com.utilitysuite.jobs.schematic.mc.BuildAssist ba =
                        (com.utilitysuite.jobs.schematic.mc.BuildAssist) f.get(tk);
                s.note("dome scaffold: recorded " + ba.scaffoldCount() + ", cleanup pending " + ba.cleanupPending()
                        + ", notice " + ba.cleanupNotice() + ", walker " + ba.walkPhase() + ", player "
                        + H.posStr());
            } catch (ReflectiveOperationException e) {
                s.note("dome scaffold: " + e);
            }
            tk.cancel();
            var goals = Services.goalsOrNull();
            if (goals != null) {
                goals.activeJob().ifPresent(a -> goals.cancel(a.jobId()));
                for (var q : goals.queuedJobs()) {
                    goals.cancel(q.jobId());
                }
            }
        });
        s.cmd(() -> Arena.fill(-12, 1, -12, -2, 6, -6, "air"));
    }

    // ---- 7. riptide launch ----------------------------------------------------------------------------------------

    private static void riptide(Scenario s) {
        s.cmd("clear @s");
        s.cmd("weather rain");
        s.cmd("item replace entity @s armor.chest with minecraft:elytra");
        s.cmd("item replace entity @s weapon.mainhand with minecraft:trident[enchantments={levels:{\"minecraft:riptide\":3}}]");
        s.cmd("give @s minecraft:firework_rocket 8");
        // stand in the arena pool (1 deep): wet and on the ground
        s.cmd(() -> "tp @s " + (ax(6) + 0.5) + " " + ay(0) + " " + (az(-6) + 0.5) + " 0 0");
        s.waitTicks(20);
        double[] y0 = new double[1];
        boolean[] spin = new boolean[1];
        boolean[] started = new boolean[1];
        String[] phases = {""};
        double[] maxY = {0};
        s.run("riptide route on", () -> {
            H.setting(TravelAutopilot.ID, "elytra_riptide_launch", true);
            H.enable(TravelAutopilot.ID, true);
            y0[0] = H.p().getY();
            maxY[0] = y0[0];
            ITravel travel = Services.travelOrNull();
            started[0] = travel != null && travel.startRoute(ax(6), ay(1), az(-200), ITravel.RouteMode.ELYTRA);
        });
        s.waitUpTo("riptide launch", 20 * 8, () -> {
            TravelAutopilot ap = H.module(TravelAutopilot.ID);
            String ph = ap.phase();
            if (ph != null && !phases[0].endsWith(ph)) {
                phases[0] += (phases[0].isEmpty() ? "" : ">") + ph;
            }
            spin[0] |= H.p().isAutoSpinAttack();
            maxY[0] = Math.max(maxY[0], H.p().getY());
            return spin[0] && maxY[0] > y0[0] + 4;
        });
        s.run("riptide result", () -> {
            TravelAutopilot ap = H.module(TravelAutopilot.ID);
            s.check("riptide launch: the elytra autopilot launches with the Riptide trident", started[0] && spin[0]
                            && maxY[0] > y0[0] + 4,
                    "route started " + started[0] + ", phases " + phases[0] + ", spin attack seen " + spin[0]
                            + Harness.fmt(", rose %.1f blocks", maxY[0] - y0[0]) + ", wet " + H.p().isInWaterOrRain()
                            + "; " + H.arbiterState());
            ap.stop();
            H.enable(TravelAutopilot.ID, false);
            H.setting(TravelAutopilot.ID, "elytra_riptide_launch", false);
        });
        s.cmd("weather clear");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.cmd("clear @s");
    }

    // ---- 8. debris blasting (Nether) ------------------------------------------------------------------------------

    private static DebrisBlastCycle cycle(DebrisBlastModule m) {
        return (DebrisBlastCycle) field(m, "cycle");
    }

    private static void debris(Scenario s) {
        s.cmd(NETHER + "forceload add " + (NX - 16) + " " + (NZ - 16) + " " + (NX + 64) + " " + (NZ + 16));
        s.waitTicks(40);
        s.cmd(NETHER + "fill " + (NX - 5) + " " + (NY - 2) + " " + (NZ - 8) + " " + (NX + 25) + " " + (NY + 7) + " "
                + (NZ + 8) + " minecraft:netherrack");
        s.cmd(NETHER + "fill " + (NX - 5) + " " + (NY - 2) + " " + (NZ - 8) + " " + (NX + 25) + " " + (NY - 1) + " "
                + (NZ + 8) + " minecraft:obsidian");
        s.cmd(NETHER + "fill " + (NX - 2) + " " + NY + " " + NZ + " " + NX + " " + (NY + 1) + " " + NZ + " air");
        s.cmd("clear @s");
        s.cmd("give @s minecraft:diamond_pickaxe[enchantments={levels:{\"minecraft:efficiency\":5}}]");
        s.cmd("give @s minecraft:netherrack 32");
        s.cmd("effect give @s minecraft:haste 300 1 true");
        s.cmd(NETHER + "tp @s " + NX + ".5 " + NY + " " + NZ + ".5 -90 0");
        s.waitUpTo("in the Nether", 20 * 15, () -> H.mc().level.dimension() == Level.NETHER
                && Math.abs(H.p().getX() - (NX + 0.5)) < 1);
        s.waitTicks(40);
        // --- no charges: tunnels to the first pocket, then waits ---
        s.run("debris blasting on (no charges)", () -> {
            H.setting(DebrisBlastModule.ID, "pocket_every", 3);
            H.setting(DebrisBlastModule.ID, "prefer_bed", true);
            H.setting(DebrisBlastModule.ID, "relaxed", false);
            H.enable(DebrisBlastModule.ID, true);
        });
        int[] lastMove = {0};
        int[] diagClock = {0};
        s.waitUpTo("tunnel until the no-charge wait", 20 * 90, () -> {
            DebrisBlastModule m = H.module(DebrisBlastModule.ID);
            if (++diagClock[0] % 100 == 0) {
                s.note("debris tunnel t=" + diagClock[0] + ": " + handsDiag(m));
            }
            walkTunnel(m, lastMove);
            return m.status().startsWith("Debris blast waiting") || !m.isEnabled();
        });
        s.run("no-charge result", () -> {
            DebrisBlastModule m = H.module(DebrisBlastModule.ID);
            s.check("debris blasting: with no TNT/bed it stays on and says it is waiting", m.isEnabled()
                            && m.status().contains("NO_CHARGE"),
                    "enabled " + m.isEnabled() + ", status '" + m.status() + "', player " + H.posStr());
        });
        // --- with a bed: a full cycle with the Lava Plug / Vein Work hand-offs ---
        s.cmd("give @s minecraft:red_bed");
        s.cmd("give @s minecraft:flint_and_steel");
        s.run("face east", () -> Harness.command("tp @s " + H.p().getX() + " " + H.p().getY() + " " + H.p().getZ()
                + " -90 0"));
        DebrisRun run = new DebrisRun();
        s.step("debris blast cycle", t -> run.tick(s, t));
        Shots.add(s, "fieldjobs-debris");
        s.run("debris result", () -> {
            DebrisBlastModule m = H.module(DebrisBlastModule.ID);
            DebrisBlastCycle c = cycle(m);
            String where = "pocket " + run.cx + "," + run.cy + "," + run.cz + ", player " + H.posStr();
            s.check("debris blasting: phases advance tunnel > pocket > charge > retreat > shield > trigger > blast",
                    run.order.toString().contains("TUNNEL>POCKET>PLACE_CHARGE>RETREAT>PLACE_SHIELD>TRIGGER>SETTLE"),
                    "phases " + run.order + "; status '" + m.status() + "'; " + run.notes);
            s.check("debris blasting: a blast cycle completes (bed used)", c.cycles() >= 1
                            && H.count("minecraft:red_bed") == 0,
                    "cycles " + c.cycles() + ", beds left " + H.count("minecraft:red_bed") + ", relaxed needed "
                            + run.relaxed + "; " + where);
            String lava = run.placed ? H.blockId(run.cx + 1, run.cy - 1, run.cz) : "not placed";
            s.check("debris blasting: exposed lava is plugged (Lava Plug hand-off)", run.placed
                            && !H.block(run.cx + 1, run.cy - 1, run.cz).getFluidState().is(
                            net.minecraft.tags.FluidTags.LAVA),
                    "lava cell now " + lava + ", plug status seen " + run.sawPlug + ", lava plug busy "
                            + ((LavaPlugModule) H.module(LavaPlugModule.ID)).isBusy() + "; " + where);
            String deb = run.placed ? H.blockId(run.cx + 1, run.cy, run.cz - 1) : "not placed";
            s.check("debris blasting: revealed ancient debris is mined (Vein Work hand-off)", run.placed
                            && !deb.equals("minecraft:ancient_debris"),
                    "debris cell now " + deb + ", ancient debris carried " + H.count("minecraft:ancient_debris")
                            + ", vein busy " + ((VeinWorkModule) H.module(VeinWorkModule.ID)).isBusy()
                            + ", mine status seen " + run.sawMine + "; " + where + "; " + run.notes);
            H.enable(DebrisBlastModule.ID, false);
            H.setting(DebrisBlastModule.ID, "relaxed", false);
            H.setting(VeinWorkModule.ID, "relaxed", false);
        });
    }

    /** Walks the player one block along the tunnel (east) once both cells ahead are open, until the pocket is due. */
    private static void walkTunnel(DebrisBlastModule m, int[] lastMove) {
        DebrisBlastCycle c = cycle(m);
        lastMove[0]++;
        if (c == null || c.phase() != DebrisBlastCycle.Phase.TUNNEL || lastMove[0] < 6) {
            return;
        }
        int since = intField(m, "blocksSincePocket");
        if (since >= 3) {
            return;
        }
        int x = H.floorX() + 1, y = H.floorY(), z = H.floorZ();
        if (H.block(x, y, z).isAir() && H.block(x, y + 1, z).isAir()) {
            Harness.command("tp @s " + (x + 0.5) + " " + y + " " + (z + 0.5));
            lastMove[0] = 0;
        }
    }

    /** The user's side of one debris cycle: walk the tunnel, step back for the charge, step in for the hand-offs. */
    private static final class DebrisRun {
        final StringBuilder order = new StringBuilder();
        final StringBuilder notes = new StringBuilder();
        final int[] lastMove = {0};
        DebrisBlastCycle.Phase last;
        int phaseTicks;
        int cx = Integer.MIN_VALUE, cy, cz;
        boolean placed, retreated, relaxed, steppedIn, sawPlug, sawMine;
        int cyclesAtEnd = -1;
        int handOffTicks;
        boolean grabNoted;

        boolean tick(Scenario s, int t) {
            DebrisBlastModule m = H.module(DebrisBlastModule.ID);
            DebrisBlastCycle c = cycle(m);
            if (!m.isEnabled()) {
                notes.append("module switched off: ").append(m.status()).append("; ");
                return true;
            }
            DebrisBlastCycle.Phase p = c.phase();
            if (p != last) {
                order.append(order.length() == 0 ? "" : ">").append(p);
                s.note("debris t=" + t + " phase " + p + ", status '" + m.status() + "', player " + H.posStr()
                        + ", " + H.fields(m, "blocksSincePocket", "cx", "cy", "cz")
                        + (cx == Integer.MIN_VALUE ? "" : ", charge cell " + H.blockId(cx, cy, cz)) + ", beds "
                        + H.count("minecraft:red_bed") + ", held " + H.p().getMainHandItem());
                last = p;
                phaseTicks = 0;
            }
            phaseTicks++;
            if (phaseTicks % 200 == 0) {
                s.note("debris " + p + " for " + phaseTicks + " ticks: " + handsDiag(m));
            }
            String st = m.status();
            sawPlug |= st.startsWith("Plugging");
            sawMine |= st.startsWith("Mining");
            switch (p) {
                case TUNNEL -> {
                    if (c.cycles() >= 1) {
                        // back to tunnelling after the blast: done once the plug and mining hand-offs finished
                        boolean busy = ((LavaPlugModule) H.module(LavaPlugModule.ID)).isBusy()
                                || ((VeinWorkModule) H.module(VeinWorkModule.ID)).isBusy();
                        if (busy && phaseTicks % 100 == 0) {
                            s.note("debris hand-offs still busy after " + phaseTicks + " ticks: "
                                    + H.fields(H.module(LavaPlugModule.ID), "status")
                                    + " / vein " + H.fields(H.module(VeinWorkModule.ID), "current", "pickBusy", "running", "scanning", "relaxed") + " held " + H.p().getMainHandItem() + " slot " + H.p().getInventory().getSelectedSlot() + " destroy " + H.fields(H.mc().gameMode, "destroyProgress", "isDestroying", "destroyBlockPos") + "; vein hands: " + handsDiag(H.module(VeinWorkModule.ID)));
                        }
                        return !busy || phaseTicks > 20 * 30;
                    }
                    walkTunnel(m, lastMove);
                }
                case POCKET, PLACE_CHARGE -> {
                    if (!placed && intField(m, "pocketStep") == 1) {
                        cx = intField(m, "cx");
                        cy = intField(m, "cy");
                        cz = intField(m, "cz");
                        // a lava source in a sealed floor pit east of the charge (exposed only from above by the
                        // blast) and ancient debris in the pocket's east wall
                        Harness.command("setblock " + (cx + 1) + " " + (cy - 1) + " " + cz + " minecraft:lava");
                        Harness.command("setblock " + (cx + 1) + " " + cy + " " + (cz - 1) + " minecraft:ancient_debris");
                        placed = true;
                    }
                }
                case RETREAT -> {
                    if (!retreated && phaseTicks > 10) {
                        // step back west along the tunnel, 3 blocks from the pocket column
                        Harness.command("tp @s " + (cx - 2.5) + " " + cy + " " + (cz + 1.5));
                        retreated = true;
                    }
                }
                case TRIGGER -> {
                    if (!relaxed && phaseTicks > 200) {
                        notes.append("legit trigger did not fire in 10 s from the stand spot (no line of sight to the"
                                + " bed); switched to relaxed; ");
                        H.setting(DebrisBlastModule.ID, "relaxed", true);
                        relaxed = true;
                    }
                }
                case PLUG_LAVA, MINE_DEBRIS -> {
                    if (!steppedIn && (st.contains("out of reach") || st.startsWith("Ancient debris at")
                            || phaseTicks > 200)) {
                        // like a user: walk back toward the crater
                        Harness.command("tp @s " + (cx - 0.5) + " " + cy + " " + (cz + 1.5));
                        steppedIn = true;
                    }
                    if (!grabNoted && !H.mc().mouseHandler.isMouseGrabbed()) {
                        // held-attack mining (vanilla continueAttack) needs the mouse grabbed; with two test clients
                        // open the window can lose focus, so the hand-off runs on the explicit less-legit setting
                        notes.append("window lost mouse grab (focus) during the hand-offs: Vein Work set to relaxed; ");
                        H.setting(VeinWorkModule.ID, "relaxed", true);
                        grabNoted = true;
                    }
                    if (++handOffTicks % 60 == 0 && handOffTicks <= 600) {
                        Object lp = H.module(LavaPlugModule.ID);
                        Object vw = H.module(VeinWorkModule.ID);
                        s.note("debris hand-off t=" + handOffTicks + ": plug " + H.fields(lp, "lavaCell", "phase",
                                "runTicks", "supX", "supY", "supZ", "supFace", "relaxed") + " " + handsDiag(lp)
                                + " | vein " + H.fields(vw, "scanning", "running", "lavaWarned", "waitTicks")
                                + " status '" + deepField(vw, "status") + "'");
                    }
                }
                default -> {
                }
            }
            if (t > 20 * 150) {
                notes.append("timed out in ").append(p).append(" (").append(st).append("); ");
                return true;
            }
            return false;
        }
    }

    // ---- 9. piglin barter (Nether) --------------------------------------------------------------------------------

    private static void piglin(Scenario s) {
        int px = NX + 40;
        s.cmd(NETHER + "fill " + (px - 2) + " " + (NY - 2) + " " + (NZ - 7) + " " + (px + 12) + " " + (NY + 5) + " "
                + (NZ + 7) + " minecraft:obsidian");
        s.cmd(NETHER + "fill " + (px - 1) + " " + NY + " " + (NZ - 6) + " " + (px + 11) + " " + (NY + 4) + " "
                + (NZ + 6) + " air");
        s.cmd("clear @s");
        s.cmd("item replace entity @s armor.head with minecraft:golden_helmet");
        s.cmd("item replace entity @s weapon.mainhand with minecraft:gold_ingot 8"); // barter works from the main hand
        s.cmd(NETHER + "tp @s " + px + ".5 " + NY + " " + NZ + ".5 -90 0");
        s.waitTicks(20);
        Piglin[] piglin = new Piglin[1];
        boolean[] admired = new boolean[1];
        int[] ingots0 = new int[1];
        s.run("piglin guard on", () -> {
            H.setting(PiglinGuard.ID, PiglinGuard.K_BARTER, true);
            H.setting(PiglinGuard.ID, PiglinGuard.K_BARTER_HAND, true);
            H.setting(PiglinGuard.ID, PiglinGuard.K_BARTER_COLLECT, false);
            H.setting(PiglinGuard.ID, PiglinGuard.K_BARTER_CADENCE, 20);
            H.enable(PiglinGuard.ID, true);
            ingots0[0] = H.count("minecraft:gold_ingot");
            Harness.command("summon minecraft:piglin " + (px + 2.5) + " " + NY + " " + (NZ + 0.5)
                    + " {PersistenceRequired:1b,IsImmuneToZombification:1b}");
        });
        s.waitUpTo("piglin admires gold", 20 * 15, () -> {
            if (piglin[0] == null) {
                var list = H.mc().level.getEntitiesOfClass(Piglin.class, H.p().getBoundingBox().inflate(10));
                piglin[0] = list.isEmpty() ? null : list.get(0);
            }
            if (piglin[0] != null && piglin[0].getOffhandItem().is(net.minecraft.world.item.Items.GOLD_INGOT)) {
                admired[0] = true;
            }
            return admired[0];
        });
        s.run("piglin hand-over result", () -> {
            Piglin g = piglin[0];
            Entity se = serverEntity(Level.NETHER, g);
            s.check("piglin barter: the ingot is handed over (piglin admires it)", admired[0]
                            && H.count("minecraft:gold_ingot") < ingots0[0],
                    "piglin " + (g == null ? "missing" : "at " + Harness.fmt("%.1f", Math.sqrt(g.distanceToSqr(H.p())))
                            + " blocks, offhand " + g.getOffhandItem() + ", aggressive " + g.isAggressive())
                            + ", server offhand " + (se instanceof Piglin sp ? sp.getOffhandItem() : "?")
                            + ", ingots " + ingots0[0] + " -> " + H.count("minecraft:gold_ingot") + "; "
                            + H.arbiterState());
        });
        int[] pearls0 = new int[1];
        s.run("collect on + drop", () -> {
            H.setting(PiglinGuard.ID, PiglinGuard.K_BARTER_COLLECT, true);
            pearls0[0] = H.count("minecraft:ender_pearl");
            Harness.command("summon minecraft:item " + (px + 4.5) + " " + NY + " " + (NZ + 4.5)
                    + " {Item:{id:\"minecraft:ender_pearl\",count:1},PickupDelay:0s}");
        });
        s.waitUpTo("drop picked up", 20 * 20, () -> H.count("minecraft:ender_pearl") > pearls0[0]);
        s.run("piglin collect result", () -> {
            s.check("piglin barter: a nearby worthwhile drop is collected", H.count("minecraft:ender_pearl")
                            > pearls0[0],
                    "ender pearls " + pearls0[0] + " -> " + H.count("minecraft:ender_pearl") + ", player " + H.posStr()
                            + "; " + H.arbiterState());
            H.enable(PiglinGuard.ID, false);
        });
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd(NETHER + "forceload remove " + (NX - 16) + " " + (NZ - 16) + " " + (NX + 64) + " " + (NZ + 16));
    }
}
