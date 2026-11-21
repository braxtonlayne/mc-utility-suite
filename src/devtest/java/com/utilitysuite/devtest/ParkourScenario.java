package com.utilitysuite.devtest;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.travel.movement.FallClutchPlanner;
import com.utilitysuite.travel.movement.MovementAssist;
import com.utilitysuite.travel.movement.ParkourCourse;
import com.utilitysuite.travel.movement.ParkourRun;
import com.utilitysuite.travel.terrain.TerrainAssist;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/**
 * Scenario 8b: the movement benchmark in the real game, and the escape path.
 * <ul>
 *   <li>Builds {@link ParkourCourse#standard()} (19 segments, easiest first: gaps, height changes, sideways landings,
 *       strips, momentum, low ceilings) in the sky beside the arena. A lease holds only FORWARD + SPRINT and turns
 *       the camera (through the capped smoother) toward the next pad, like a player who never presses jump; Movement
 *       Assist must time every jump and air correction. Reports per segment.</li>
 *   <li>Escape path: a zombie (no AI) next to the player; the overlay must show a route away from it.</li>
 * </ul>
 * Fall damage is off while the course runs; a fall teleports back to the arena.
 */
final class ParkourScenario {

    /** Course origin (course (0,0,0)) relative to the arena centre; y = Arena.FLOOR. */
    static final int OX = 40;
    static final int OZ = -60;
    static final int TIMEOUT_TICKS = 20 * 70;

    private ParkourScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("8b-parkour", "Movement benchmark: progressive parkour course, escape path");
        ParkourCourse course = ParkourCourse.standard();
        int[] b = course.bounds();
        ControlLease[] lease = new ControlLease[1];
        ParkourRun[] run = new ParkourRun[1];
        int[] tick = new int[1];
        int[] jumpsBefore = new int[1];
        int[] correctionsBefore = new int[1];
        boolean[] shot = new boolean[3];
        Arena.ensure(s);
        s.cmd("gamerule fallDamage false");
        s.cmd(() -> "forceload add " + (Arena.cx + OX + b[0] - 4) + " " + (Arena.cz + OZ + b[2] - 4) + " "
                + (Arena.cx + OX + b[3] + 4) + " " + (Arena.cz + OZ + b[5] + 4));
        s.waitTicks(40);
        // clear the air volume in slices (fill is capped at 32768 blocks), then the course boxes
        for (int z0 = b[2] - 2; z0 <= b[5] + 2; z0 += 40) {
            int zs = z0;
            s.cmd(() -> fillWorld(b[0] - 3, b[1] - 1, zs, b[3] + 3, b[4] + 5, Math.min(zs + 39, b[5] + 2), "air"));
        }
        for (int[] box : course.boxes()) {
            s.cmd(() -> fillWorld(box[0], box[1], box[2], box[3], box[4], box[5], "minecraft:smooth_stone"));
        }
        s.cmd(() -> "tp @s " + (Arena.cx + OX + course.startX()) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz + OZ + course.startZ()) + " 0 15");
        s.waitTicks(20);
        s.run("assist on, runner lease", () -> {
            H.enable(TerrainAssist.ID, false); // SafeWalk would (rightly) refuse the drops that cost health
            H.setting(MovementAssist.ID, "edge_jump", true);
            H.setting(MovementAssist.ID, "air_steer", true);
            H.setting(MovementAssist.ID, "overlay", true);
            H.setting(MovementAssist.ID, "assist_automation", true);
            H.enable(MovementAssist.ID, true);
            MovementAssist ma = TravelBootstrap.movement();
            jumpsBefore[0] = ma.edgeJumps();
            correctionsBefore[0] = ma.corrections();
            run[0] = new ParkourRun(course);
            lease[0] = Services.arbiter().request(LeaseRequest.of("parkour_runner", ControlPriority.TRAVEL, null,
                    ControlChannel.MOVEMENT, ControlChannel.CAMERA)).orElseThrow();
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, true);
            Services.input().setKeyHeld(lease[0], SimKey.SPRINT, true);
        });
        s.waitUpTo("run the course", TIMEOUT_TICKS, () -> {
            var p = H.p();
            double x = p.getX() - Arena.cx - OX;
            double y = p.getY() - Arena.FLOOR;
            double z = p.getZ() - Arena.cz - OZ;
            boolean going = run[0].update(++tick[0], x, y, z, p.onGround());
            Services.input().setRotationTarget(lease[0],
                    new RotationTarget(run[0].aimYaw(x, z), 15f, RotationMode.VISIBLE, 1f));
            int seg = run[0].cleared() + 1;
            if (!p.onGround()) {
                if (seg == 3 && !shot[0]) {
                    shot[0] = true;
                    Shots.take(s, "parkour-3-gap-airborne");
                } else if (seg == 14 && !shot[1]) {
                    shot[1] = true;
                    Shots.take(s, "parkour-low-ceiling");
                } else if (seg == 19 && !shot[2]) {
                    shot[2] = true;
                    Shots.take(s, "parkour-final-strip");
                }
            }
            return !going;
        });
        s.run("benchmark result", () -> {
            ParkourRun r = run[0];
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, false);
            Services.input().setKeyHeld(lease[0], SimKey.SPRINT, false);
            lease[0].close();
            MovementAssist ma = TravelBootstrap.movement();
            int total = course.segments().size();
            s.check("parkour benchmark: the assist clears every segment (" + total + ", progressively harder)",
                    r.finished(), Harness.fmt("cleared %d/%d%s in %d ticks; edge jumps %d, air corrections %d",
                            r.cleared(), total, r.fellOnSegment() > 0 ? ", fell on segment " + r.fellOnSegment()
                                    + " (" + course.segments().get(r.fellOnSegment() - 1).name() + ")" : "",
                            tick[0], ma.edgeJumps() - jumpsBefore[0], ma.corrections() - correctionsBefore[0]));
            for (String line : r.report().split("\n")) {
                s.note(line);
            }
        });
        Shots.add(s, "parkour-finish");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.waitTicks(10);
        blockLane(s, lease, "wall", 12, true);
        blockLane(s, lease, "gap", 18, false);
        waterClutch(s, 26);
        blockClutch(s, 34, "hay_block", FallClutchPlanner.Kind.HAY, false, 3f);
        blockClutch(s, 42, "cobweb", FallClutchPlanner.Kind.COBWEB, false, 1f);
        // --- escape path from a zombie ---
        s.cmd(() -> "summon minecraft:zombie " + (Arena.cx + 4.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 0.5)
                + " {NoAI:1b,PersistenceRequired:1b}");
        s.cmd(() -> Arena.tp(0.5, 0.5, 90f));
        s.waitUpTo("escape path shows", 60, () -> TravelBootstrap.movement().escapeLength() >= 2);
        s.run("escape result", () -> {
            MovementAssist ma = TravelBootstrap.movement();
            int n = ma.escapeLength();
            boolean away = false;
            String detail = "no path";
            if (n >= 2) {
                long end = ma.escapeCell(n - 1);
                double zx = Arena.cx + 4.5;
                double zz = Arena.cz + 0.5;
                double startD = Math.hypot(H.p().getX() - zx, H.p().getZ() - zz);
                double endD = Math.hypot(Packed.x(end) + 0.5 - zx, Packed.z(end) + 0.5 - zz);
                away = endD > startD + 3;
                detail = Harness.fmt("%d steps, end %d %d %d, distance to the zombie %.1f -> %.1f", n,
                        Packed.x(end), Packed.y(end), Packed.z(end), startD, endD);
            }
            s.check("escape path leads away from a nearby zombie", away, detail);
        });
        Shots.add(s, "movement-escape-path");
        s.always("clean up", () -> {
            Harness.command("kill @e[type=minecraft:zombie]");
            Harness.command("gamerule fallDamage true");
            H.setting(MovementAssist.ID, "assist_automation", false);
            H.enable(MovementAssist.ID, false);
            if (lease[0] != null && lease[0].isActive()) {
                lease[0].close();
            }
        });
        return s;
    }

    /**
     * Parkour blocks. {@code wall}: a 2-high wall across the lane at z+9; the runner sprints at it and holds jump
     * from two blocks out (a player trying to get over). Otherwise a 4-air gap (z+11..z+14, one block more than a
     * sprint jump clears) and the runner holds only forward + sprint. Either way the run must end past the obstacle
     * with a block placed.
     */
    private static void blockLane(Scenario s, ControlLease[] lease, String name, int dx, boolean wall) {
        int[] before = new int[1];
        double[] minY = {Double.MAX_VALUE};
        boolean[] shot = new boolean[1];
        s.cmd(() -> fillWorld(dx - 3, 0, -1, dx + 3, 4, 27, "air"));
        if (wall) {
            s.cmd(() -> fillWorld(dx - 1, 0, 0, dx + 1, 0, 22, "minecraft:smooth_stone"));
            s.cmd(() -> fillWorld(dx - 2, 1, 9, dx + 2, 2, 9, "minecraft:stone_bricks"));
        } else {
            s.cmd(() -> fillWorld(dx - 1, 0, 0, dx + 1, 0, 10, "minecraft:smooth_stone"));
            s.cmd(() -> fillWorld(dx - 1, 0, 15, dx + 1, 0, 25, "minecraft:smooth_stone"));
        }
        s.cmd("clear @s");
        s.cmd("give @s minecraft:cobblestone 32");
        s.cmd(() -> "tp @s " + (Arena.cx + OX + dx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + OZ + 0.5)
                + " 0 10");
        s.waitTicks(15);
        s.run(name + ": runner", () -> {
            H.setting(MovementAssist.ID, "parkour_blocks", true);
            before[0] = TravelBootstrap.movement().blocksPlaced();
            lease[0] = Services.arbiter().request(LeaseRequest.of("parkour_runner", ControlPriority.TRAVEL, null,
                    ControlChannel.MOVEMENT)).orElseThrow();
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, true);
            Services.input().setKeyHeld(lease[0], SimKey.SPRINT, true);
        });
        double goalZ = wall ? 10.5 : 15.5;
        s.waitUpTo(name + ": run", 20 * 10, () -> {
            var p = H.p();
            double z = p.getZ() - Arena.cz - OZ;
            minY[0] = Math.min(minY[0], p.getY());
            if (wall && z >= 7.0) {
                Services.input().setKeyHeld(lease[0], SimKey.JUMP, true);
            }
            if (!shot[0] && TravelBootstrap.movement().blocksPlaced() > before[0]) {
                shot[0] = true;
                Shots.take(s, "parkour-blocks-" + name);
            }
            return (p.onGround() && z > goalZ) || p.getY() < Arena.FLOOR - 3;
        });
        s.run(name + ": result", () -> {
            Services.input().setKeyHeld(lease[0], SimKey.JUMP, false);
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, false);
            Services.input().setKeyHeld(lease[0], SimKey.SPRINT, false);
            lease[0].close();
            var p = H.p();
            MovementAssist ma = TravelBootstrap.movement();
            double z = p.getZ() - Arena.cz - OZ;
            s.check("parkour blocks: " + (wall ? "jumping at a 2-high wall, a block to land on, over it"
                            : "a gap one block too long, a block extends the far side, across"),
                    z > goalZ && p.getY() >= Arena.FLOOR + 0.99 && ma.blocksPlaced() > before[0],
                    Harness.fmt("z=+%.2f (goal +%.1f) y=%.2f min y %.2f; blocks placed %d, plans %d, aborts %d", z,
                            goalZ, p.getY(), minY[0], ma.blocksPlaced() - before[0], ma.blockPlans(),
                            ma.blockAborts()));
        });
    }

    /**
     * Water clutch: fall damage on, a water bucket in the hotbar, dropped from 14 blocks (11 damage, enough to
     * clutch, not enough to kill if it fails). Must land unhurt, with the water scooped back into the bucket.
     */
    private static void waterClutch(Scenario s, int dx) {
        int drop = 14;
        float[] hp = new float[1];
        int[] before = new int[1];
        s.cmd(() -> fillWorld(dx - 3, 0, -3, dx + 3, drop + 4, 3, "air"));
        s.cmd(() -> fillWorld(dx - 2, 0, -2, dx + 2, 0, 2, "minecraft:smooth_stone"));
        s.cmd("clear @s");
        s.cmd("give @s minecraft:water_bucket");
        s.cmd("effect give @s minecraft:instant_health 1 10 true");
        s.cmd("gamerule fallDamage true");
        s.waitTicks(10);
        s.run("clutch: drop", () -> {
            H.setting(MovementAssist.ID, "water_clutch", true);
            before[0] = TravelBootstrap.movement().fallClutches();
            hp[0] = H.p().getHealth();
        });
        s.cmd(() -> "tp @s " + (Arena.cx + OX + dx + 0.5) + " " + (Arena.FLOOR + 1 + drop) + " "
                + (Arena.cz + OZ + 0.5) + " 0 0");
        boolean[] shot = new boolean[1];
        s.waitUpTo("clutch: fall", 20 * 6, () -> {
            if (!shot[0] && TravelBootstrap.movement().fallClutches() > before[0]) {
                shot[0] = true;
                Shots.take(s, "water-clutch");
            }
            return H.p().onGround() && !H.p().isInWater();
        });
        s.waitTicks(30); // scoop + turn back
        s.run("clutch: result", () -> {
            var p = H.p();
            MovementAssist ma = TravelBootstrap.movement();
            int water = 0;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    m.set(Arena.cx + OX + dx + x, Arena.FLOOR + 1, Arena.cz + OZ + z);
                    if (p.level().getFluidState(m).isSource()) {
                        water++;
                    }
                }
            }
            boolean bucket = false;
            for (int i = 0; i < 9; i++) {
                bucket |= p.getInventory().getItem(i).is(Items.WATER_BUCKET);
            }
            s.check("water clutch: a " + drop + "-block fall lands in poured water, unhurt, water picked back up",
                    ma.fallClutches() > before[0] && p.getHealth() >= hp[0] && bucket && water == 0,
                    Harness.fmt("clutches %d, pickups %d, health %.1f -> %.1f, water bucket back %s, water left %d,"
                                    + " pitch now %.1f", ma.fallClutches() - before[0], ma.clutchPickups(), hp[0],
                            p.getHealth(), bucket, water, p.getXRot()));
            Harness.command("gamerule fallDamage false");
        });
    }

    /**
     * Block clutch without a bucket: only {@code item} in the hotbar, dropped from 14 blocks (11 damage). With
     * {@code wall}, a stone wall stands right beside the fall (east), for ladders. Must use that kind and lose no more
     * than {@code maxLoss} health.
     */
    private static void blockClutch(Scenario s, int dx, String item, FallClutchPlanner.Kind kind, boolean wall,
                                    float maxLoss) {
        int drop = 14;
        float[] hp = new float[1];
        int[] before = new int[3];
        s.cmd(() -> fillWorld(dx - 3, 0, -3, dx + 3, drop + 4, 3, "air"));
        s.cmd(() -> fillWorld(dx - 2, 0, -2, dx + 2, 0, 2, "minecraft:smooth_stone"));
        if (wall) {
            s.cmd(() -> fillWorld(dx + 1, 1, -1, dx + 1, drop + 2, 1, "minecraft:stone"));
        }
        s.cmd("clear @s");
        s.cmd("give @s minecraft:" + item + " 4");
        s.cmd("effect give @s minecraft:instant_health 1 10 true");
        s.cmd("gamerule fallDamage true");
        s.waitTicks(10);
        s.run(kind + " clutch: drop", () -> {
            H.setting(MovementAssist.ID, "water_clutch", true);
            H.setting(MovementAssist.ID, "clutch_blocks", true);
            before[0] = TravelBootstrap.movement().fallClutches();
            before[1] = TravelBootstrap.movement().fallClutches(kind);
            before[2] = TravelBootstrap.movement().clutchPlans();
            hp[0] = H.p().getHealth();
        });
        // a wall: stand close to it (x .7), as a fall along a cliff face would be
        s.cmd(() -> "tp @s " + (Arena.cx + OX + dx + (wall ? 0.7 : 0.5)) + " " + (Arena.FLOOR + 1 + drop) + " "
                + (Arena.cz + OZ + 0.5) + " 0 0");
        boolean[] shot = new boolean[1];
        s.waitUpTo(kind + " clutch: fall", 20 * 8, () -> {
            if (!shot[0] && TravelBootstrap.movement().fallClutches() > before[0]) {
                shot[0] = true;
                Shots.take(s, kind.name().toLowerCase(java.util.Locale.ROOT) + "-clutch");
            }
            // left the teleport height, then down and still
            return H.p().getY() < Arena.FLOOR + drop - 1 && H.p().onGround() && H.p().getDeltaMovement().y > -0.1;
        });
        s.waitTicks(30);
        s.run(kind + " clutch: result", () -> {
            var p = H.p();
            MovementAssist ma = TravelBootstrap.movement();
            int used = ma.fallClutches(kind) - before[1];
            s.check(kind + " clutch: a " + drop + "-block fall with only " + item + " in the hotbar",
                    used > 0 && p.getHealth() >= hp[0] - maxLoss,
                    Harness.fmt("%s clutches %d, plans %d, last: %s, health %.1f -> %.1f (allowed loss %.1f), y %.2f,"
                                    + " pitch now %.1f", kind, used, ma.clutchPlans() - before[2], ma.clutchNote(),
                            hp[0],
                            p.getHealth(), maxLoss, p.getY() - Arena.FLOOR, p.getXRot()));
            Harness.command("gamerule fallDamage false");
        });
        s.cmd(() -> fillWorld(dx - 3, 1, -3, dx + 3, drop + 4, 3, "air"));
    }

    private static String fillWorld(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        int bx = Arena.cx + OX;
        int by = Arena.FLOOR;
        int bz = Arena.cz + OZ;
        return "fill " + (bx + x0) + " " + (by + y0) + " " + (bz + z0) + " " + (bx + x1) + " " + (by + y1) + " "
                + (bz + z1) + " " + block;
    }
}
