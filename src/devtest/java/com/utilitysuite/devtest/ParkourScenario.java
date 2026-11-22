package com.utilitysuite.devtest;

import org.lwjgl.glfw.GLFW;
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
import com.utilitysuite.travel.movement.LevelShapes;
import com.utilitysuite.travel.movement.MovementPhysics;
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
        blockLane(s, lease, "wall", 12, true, false);
        blockLane(s, lease, "gap", 18, false, false);
        waterClutch(s, 26, false);
        // the autopilot runs the same reflexes with Movement Assist itself switched off
        waterClutch(s, 50, true);
        blockLane(s, lease, "gap", 58, false, true);
        blockClutch(s, 34, "hay_block", FallClutchPlanner.Kind.HAY, false, 3f);
        blockClutch(s, 42, "cobweb", FallClutchPlanner.Kind.COBWEB, false, 1f);
        // --- control-model lanes: narrow landings (fence, wall, pane, corner) per control mode, override, parity ---
        controlModelLanes(s, course);
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
     * from two blocks out (a player trying to get over). Otherwise a 5-air gap (z+11..z+15, one block more than a
     * perfect last-tick sprint jump clears) and the runner holds only forward + sprint. Either way the run must end past the obstacle
     * with a block placed.
     */
    private static void blockLane(Scenario s, ControlLease[] lease, String name, int dx, boolean wall,
                                  boolean viaAutopilot) {
        String tag = viaAutopilot ? "autopilot (Movement Assist off) " : "";
        int[] before = new int[1];
        double[] minY = {Double.MAX_VALUE};
        boolean[] shot = new boolean[1];
        s.cmd(() -> fillWorld(dx - 3, 0, -1, dx + 3, 4, 27, "air"));
        if (wall) {
            s.cmd(() -> fillWorld(dx - 1, 0, 0, dx + 1, 0, 22, "minecraft:smooth_stone"));
            s.cmd(() -> fillWorld(dx - 2, 1, 9, dx + 2, 2, 9, "minecraft:stone_bricks"));
        } else {
            s.cmd(() -> fillWorld(dx - 1, 0, 0, dx + 1, 0, 10, "minecraft:smooth_stone"));
            s.cmd(() -> fillWorld(dx - 1, 0, 16, dx + 1, 0, 25, "minecraft:smooth_stone"));
        }
        s.cmd("clear @s");
        s.cmd("give @s minecraft:cobblestone 32");
        s.cmd(() -> "tp @s " + (Arena.cx + OX + dx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + OZ + 0.5)
                + " 0 10");
        s.waitTicks(15);
        s.run(name + ": runner", () -> {
            H.setting(MovementAssist.ID, "parkour_blocks", !viaAutopilot);
            autopilotOnly(viaAutopilot);
            before[0] = TravelBootstrap.movement().blocksPlaced();
            lease[0] = Services.arbiter().request(LeaseRequest.of("parkour_runner", ControlPriority.TRAVEL, null,
                    ControlChannel.MOVEMENT)).orElseThrow();
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, true);
            Services.input().setKeyHeld(lease[0], SimKey.SPRINT, true);
        });
        double goalZ = wall ? 10.5 : 16.5;
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
            autopilotRestore(viaAutopilot);
            s.check(tag + "parkour blocks: " + (wall ? "jumping at a 2-high wall, a block to land on, over it"
                            : "a gap one block too long, a block extends the far side, across"),
                    z > goalZ && p.getY() >= Arena.FLOOR + 0.99 && ma.blocksPlaced() > before[0],
                    Harness.fmt("z=+%.2f (goal +%.1f) y=%.2f min y %.2f; blocks placed %d, plans %d, aborts %d", z,
                            goalZ, p.getY(), minY[0], ma.blocksPlaced() - before[0], ma.blockPlans(),
                            ma.blockAborts()));
        });
    }

    /**
     * Autopilot lanes: Movement Assist off (and its own reflex toggle off too), the autopilot on with its defaults.
     * Whatever reflex fires now can only have come through the autopilot.
     */
    private static void autopilotOnly(boolean on) {
        if (!on) {
            return;
        }
        H.enable(MovementAssist.ID, false);
        com.utilitysuite.core.CoreBootstrap.setAutopilot(true);
        if (TravelBootstrap.movement().isEnabled() || !com.utilitysuite.core.CoreBootstrap.autopilot().isOn()) {
            throw new IllegalStateException("autopilot lane setup failed");
        }
    }

    private static void autopilotRestore(boolean on) {
        if (!on) {
            return;
        }
        com.utilitysuite.core.CoreBootstrap.setAutopilot(false);
        H.setting(MovementAssist.ID, "water_clutch", true);
        H.setting(MovementAssist.ID, "parkour_blocks", true);
        H.enable(MovementAssist.ID, true);
    }

    /**
     * Water clutch: fall damage on, a water bucket in the hotbar, dropped from 14 blocks (11 damage, enough to
     * clutch, not enough to kill if it fails). Must land unhurt, with the water scooped back into the bucket.
     */
    private static void waterClutch(Scenario s, int dx, boolean viaAutopilot) {
        String tag = viaAutopilot ? "autopilot (Movement Assist off) " : "";
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
            H.setting(MovementAssist.ID, "water_clutch", !viaAutopilot);
            autopilotOnly(viaAutopilot);
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
            autopilotRestore(viaAutopilot);
            s.check(tag + "water clutch: a " + drop + "-block fall lands in poured water, unhurt, water picked back up",
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

    // ==== control-model lanes (2026-11-22 parkour control model, design section 11) ===================================
    // None of this has been run in game yet. Geometry and tap timings are first guesses; tune them in game.

    /** Control modes of Movement Assist (profile key {@code control_mode}, written by string). */
    private enum Mode { CORRECTIONS, CO_PILOT, TAKEOVER }

    /** How the override lane presses sneak during the committed jump. */
    private enum Override { NONE, SCRIPTED, PHYSICAL }

    /**
     * One narrow-landing lane. The post sits at block y 0 (floor level); {@code top} is the height of its collision
     * top above the block base (fence and wall 1.5, pane 1.0); {@code limit} is how far the body centre may be from
     * the post centre on either axis and still be supported (post half width + 0.3).
     */
    private record Kind(String name, String landingClass, String block, int gap, double top, double limit,
                        boolean corner, int columnTo, boolean open) {
    }

    private static final Kind FENCE = new Kind("fence-2gap", "fence", "minecraft:oak_fence", 2, 1.5, 0.425, false, 0,
            false);
    private static final Kind WALL = new Kind("wall-3gap", "wall", "minecraft:cobblestone_wall", 3, 1.5, 0.55,
            false, 0, false);
    private static final Kind PANE = new Kind("pane-1gap", "pane", "minecraft:glass_pane", 1, 1.0, 0.3625, false, 0,
            false);
    /*
     * Corner lanes (rebuilt 2026-11-22, see docs/state/run-2026-11-22-open-items.md "Parkour lanes"): the post is one
     * block to the side (x + 1) behind a stone column at the platform edge. The column is ONE block deep (z 11); the
     * fence post is across 2 air blocks, the wall post across 3. As first built both posts were across 3 behind a
     * column two blocks deep: the replica search (ParkourLaneReplicaTest) finds touchdowns there only as frame-perfect
     * wall slides and no clean technique in any control envelope. Those two stay in the run as OPEN lanes: their
     * result is a note, not a check.
     */
    private static final Kind CORNER_FENCE = new Kind("corner-fence-2gap", "corner-fence", "minecraft:oak_fence", 2,
            1.5, 0.425, true, 11, false);
    private static final Kind CORNER_WALL = new Kind("corner-wall-3gap", "corner-wall",
            "minecraft:cobblestone_wall", 3, 1.5, 0.55, true, 11, false);
    private static final Kind CORNER_FENCE_DEEP = new Kind("corner-fence-3gap-deep", "corner-fence-deep",
            "minecraft:oak_fence", 3, 1.5, 0.425, true, 12, true);
    private static final Kind CORNER_WALL_DEEP = new Kind("corner-wall-3gap-deep", "corner-wall-deep",
            "minecraft:cobblestone_wall", 3, 1.5, 0.55, true, 12, true);

    /** The corner lane's single strafe tap: LEFT held this many ticks after the jump key, for this many ticks. */
    private static final int TAP_START = 6;
    private static final int TAP_LEN = 2;
    /** The body must still be on the top this many ticks after touchdown. */
    private static final int HOLD_TICKS = 40;
    private static final double PARITY_TARGET = 0.001;
    private static final int LANE_SPACING = 8;

    /**
     * Max replica position error per landing class: [0] ground run (free-running replica from the first lane tick),
     * [1] flight (replica re-seeded at takeoff), [2] after touchdown (the flight replica continued), [3] one-step
     * (replica stepped one tick from the real state of the previous tick: physics rules alone, no drift).
     */
    private static final java.util.Map<String, double[]> PARITY = new java.util.TreeMap<>();
    /** One-step error above this is a rule mismatch worth naming (with that tick's inputs). */
    private static final double ONE_STEP_FLAG = 1e-4;

    /** Mutable state of one lane run (test scaffolding, allocation is fine). */
    private static final class LaneRun {
        /** Drift replica: seeded from the real state at the first lane tick, re-seeded at takeoff. */
        final MovementPhysics sim = new MovementPhysics();
        /** The real body after the previous tick (position, velocity, flags) as a replica state. */
        final MovementPhysics prev = new MovementPhysics();
        /** Scratch: {@link #prev} stepped one tick with this tick's inputs. */
        final MovementPhysics step = new MovementPhysics();
        final LevelShapes shapes = new LevelShapes();
        ControlLease lease;
        ControlLease[] leaseRef;
        int tick;
        int jumpTick = -1;
        int landedTick = -1;
        boolean fell;
        boolean simReady;
        /** 0 ground run, 1 flight, 2 after touchdown (parity phases, from the real onGround flag). */
        int parityPhase;
        /** Vanilla noJumpDelay rebuilt from the real inputs (the field is private in LivingEntity). */
        int jumpDelay;
        double maxGround;
        double maxFlight;
        double maxAfter;
        double maxStep;
        String firstStep = "";
        // takeoff record
        int takeoffTick = -1;
        double takeoffZ = Double.NaN;
        double takeoffX = Double.NaN;
        double takeoffSpeed = Double.NaN;
        boolean takeoffSprint;
        boolean takeoffJump;
        // commitment phase per flight tick, run-length coded
        final StringBuilder commitPhases = new StringBuilder();
        String lastCommit = "";
        String scriptInfo = "";
        double scriptDrift;
        int scriptDriftTick = -1;
        int commitFrom = -1;
        int touchdownTick = -1;
        double touchdownX = Double.NaN;
        double touchdownZ = Double.NaN;
        boolean standing;
        /** The body stood on the post top (on ground, inside the support limit) on some tick. */
        boolean touchedTop;
        int touchedTopTick = -1;
        String standNote = "never landed";
        // fresh-press phase
        boolean leftTop;
        int leftAt = -1;
        // override phase
        int overrideTick = -1;
        int overrideTicksChecked;
        int overrideMismatches;
        String overrideFirst = "";
        // diagnostics
        final StringBuilder statuses = new StringBuilder();
        /** What the assist wrote on the run-up, run-length coded (lane tick: z, x, written keys, view, script). */
        final StringBuilder groundTrace = new StringBuilder();
        String lastGround = "";
        String lastStatus = "";
        int corrBefore = -1;
        double jumpZ = Double.NaN;
        double maxZ = -1e9;
        double maxY = -1e9;
    }

    private static void controlModelLanes(Scenario s, ParkourCourse course) {
        int lz = course.bounds()[5] + 20;
        Object[] saved = new Object[4];
        boolean[] terrainWas = new boolean[1];
        boolean[] moduleWas = new boolean[1];
        ControlLease[] leaseRef = new ControlLease[1];
        // lanes: 5 kinds x 3 modes, then the override lane; the open (deep corner) lanes reuse the first slots
        int laneCount = 5 * Mode.values().length + 2;
        s.cmd(() -> "forceload add " + (Arena.cx + OX - 6) + " " + (Arena.cz + OZ + lz - 6) + " "
                + (Arena.cx + OX + LANE_SPACING * laneCount + 6) + " " + (Arena.cz + OZ + lz + 30));
        s.waitTicks(40);
        s.run("control lanes: settings", () -> {
            var profile = Services.modState().profile();
            saved[0] = profile.getString(MovementAssist.ID, "control_mode", "CO_PILOT");
            saved[1] = profile.getBool(MovementAssist.ID, "landing_hold", true);
            saved[2] = profile.getString(MovementAssist.ID, "hold_release", "FRESH_INPUT");
            saved[3] = profile.getInt(MovementAssist.ID, "hold_limit_ticks", 12);
            terrainWas[0] = H.module(TerrainAssist.ID).isEnabled();
            moduleWas[0] = H.module(MovementAssist.ID).isEnabled();
            PARITY.clear();
            H.enable(TerrainAssist.ID, false); // SafeWalk would refuse the drops; it is not under test here
            H.setting(MovementAssist.ID, "landing_hold", true);
            H.setting(MovementAssist.ID, "hold_release", "FRESH_INPUT");
            H.enable(MovementAssist.ID, true);
            Harness.command("gamerule fallDamage false");
        });
        int idx = 0;
        for (Mode mode : Mode.values()) {
            for (Kind kind : new Kind[] {FENCE, WALL, PANE, CORNER_FENCE, CORNER_WALL}) {
                landingLane(s, lz, idx++, kind, mode, Override.NONE, leaseRef);
            }
        }
        // (the scripted-sneak override lane was dropped: with the lanes on physical keys, a MOVEMENT lease is
        // rightly refused while the "user" holds W, and the physical-sneak lane is the real case)
        landingLane(s, lz, idx++, FENCE, Mode.CO_PILOT, Override.PHYSICAL, leaseRef);
        // the open lanes reuse the first four slots (each lane clears its slot before it builds): a longer row put the
        // last lanes about 200 blocks from the arena, where the body did not move after the teleport
        int slot = 0;
        for (Mode mode : new Mode[] {Mode.CO_PILOT, Mode.TAKEOVER}) {
            for (Kind kind : new Kind[] {CORNER_FENCE_DEEP, CORNER_WALL_DEEP}) {
                landingLane(s, lz, slot++, kind, mode, Override.NONE, leaseRef);
            }
        }
        s.run("control lanes: parity report", () -> {
            StringBuilder above = new StringBuilder();
            for (var e : PARITY.entrySet()) {
                double[] v = e.getValue();
                s.note(Harness.fmt("replica parity, landing class %s: max position error ground run %.6f, flight"
                                + " %.6f (re-seeded at takeoff), after touchdown %.6f, one-step %.2e (target %.3f;"
                                + " one-step expected ~1e-6)", e.getKey(), v[0], v[1], v[2], v[3], PARITY_TARGET));
                if (v[1] > PARITY_TARGET || v[2] > PARITY_TARGET) {
                    above.append(above.length() == 0 ? "" : ", ").append(e.getKey());
                }
            }
            s.note(above.length() == 0 ? "replica parity: every recorded landing class is within the target"
                    : "replica parity ABOVE TARGET (information only, switch to UNMODELLED until fixed): " + above);
        });
        s.always("control lanes: restore", () -> {
            for (int key : LANE_KEYS) {
                H.physicalKey(key, false);
            }
            if (leaseRef[0] != null && leaseRef[0].isActive()) {
                Services.input().releaseFor(leaseRef[0]);
                leaseRef[0].close();
            }
            if (saved[0] != null) {
                H.setting(MovementAssist.ID, "control_mode", saved[0]);
                H.setting(MovementAssist.ID, "landing_hold", saved[1]);
                H.setting(MovementAssist.ID, "hold_release", saved[2]);
                H.setting(MovementAssist.ID, "hold_limit_ticks", saved[3]);
                H.enable(TerrainAssist.ID, terrainWas[0]);
                H.enable(MovementAssist.ID, moduleWas[0]);
            }
            Harness.command("gamerule fallDamage true");
        });
    }

    /**
     * One lane: platform (z 0..10), gap, a single post. The script holds forward + sprint all the way, presses jump
     * once at the edge (the press comes at z >= 10.85 even when the assist already took the jump: a planned press
     * still arrives), and (corner kinds) taps LEFT once. Co-pilot and Takeover: 40 ticks after touchdown the body must
     * still be on the post top; then forward is released for 2 ticks and pressed again, and the body must leave
     * within 10 ticks. Corrections (design section 3, NUDGE: per-tick corrections, never a commitment, a held key is
     * never replaced) promises no landing from a full-sprint run-up with the user's own press; its check is the
     * landing guarantee every mode shares: a landing that was made is never walked off.
     */
    private static void landingLane(Scenario s, int lz, int idx, Kind kind, Mode mode, Override ovr,
                                    ControlLease[] leaseRef) {
        int dx = idx * LANE_SPACING + 2;
        String label = kind.name + " " + mode + (ovr == Override.NONE ? "" : " override-" + ovr);
        LaneRun r = new LaneRun();
        r.leaseRef = leaseRef;
        int postX = kind.corner ? dx + 1 : dx;
        int postZ = 11 + kind.gap;
        // absolute post centre, filled when the lane starts: Arena.cx/cz are only known after Arena.ensure has run
        double[] post = new double[2];
        double topY = Arena.FLOOR + kind.top;
        s.cmd(() -> laneFill(lz, dx - 3, -1, -1, dx + 4, 6, 24, "air"));
        s.cmd(() -> laneFill(lz, dx - 1, 0, 0, dx + 1, 0, 10, "minecraft:smooth_stone"));
        s.cmd(() -> laneFill(lz, postX, 0, postZ, postX, 0, postZ, kind.block));
        if (kind.corner) {
            s.cmd(() -> laneFill(lz, dx + 1, 0, 11, dx + 1, 3, kind.columnTo, "minecraft:stone"));
        }
        s.cmd(() -> "tp @s " + (Arena.cx + OX + dx + 0.5) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz + OZ + lz + 0.5) + " 0 10");
        s.waitTicks(15);
        s.run(label + ": start", () -> {
            post[0] = Arena.cx + OX + postX + 0.5;
            post[1] = Arena.cz + OZ + lz + postZ + 0.5;
            H.setting(MovementAssist.ID, "control_mode", mode.name());
            // Physical keys, like the owner playing. An automation lease on MOVEMENT makes the assist stand down
            // ("automation drives movement"), so leased keys would test nothing. The tp above sets the view.
            H.physicalKey(GLFW.GLFW_KEY_W, true);
            H.physicalKey(GLFW.GLFW_KEY_LEFT_CONTROL, true);
        });
        s.waitUpTo(label + ": flight and landing", 300, () -> laneTick(s, r, lz, kind, topY, post[0], post[1], ovr));
        // fresh press: release forward for 2 ticks, press it again, the body must leave the top within 10 ticks
        s.step(label + ": fresh press", t -> {
            if (!r.standing || r.fell) {
                return true;
            }
            if (t == 0) {
                H.physicalKey(GLFW.GLFW_KEY_W, false);
            } else if (t == 2) {
                H.physicalKey(GLFW.GLFW_KEY_W, true);
            }
            if (t >= 3 && !r.leftTop && !onTop(H.p(), topY, post[0], post[1], kind.limit)) {
                r.leftTop = true;
                r.leftAt = t - 2;
            }
            return r.leftTop || t >= 12;
        });
        s.run(label + ": result", () -> {
            releaseLaneKeys(r, leaseRef);
            double[] stat = PARITY.computeIfAbsent(kind.landingClass, k -> new double[4]);
            stat[0] = Math.max(stat[0], r.maxGround);
            stat[1] = Math.max(stat[1], r.maxFlight);
            stat[2] = Math.max(stat[2], r.maxAfter);
            stat[3] = Math.max(stat[3], r.maxStep);
            String parity = flightNote(r) + "; " + parityNote(r);
            boolean made = r.touchedTop;
            if (ovr == Override.NONE && kind.open) {
                // open lane (frame-perfect corner): reported, not checked; a landing that was made must still hold
                s.note(label + " run-up, what the assist wrote:" + r.groundTrace);
                s.note(label + " (open lane, no check): " + (r.standing && !r.fell ? "LANDED and stayed"
                        : made ? "touched the top and left it" : "not landed") + "; " + r.standNote + "; " + parity);
                s.check(label + ": no walk-off after a landing that was made (open lane)", !made
                        || (r.standing && !r.fell), made ? r.standNote : "no touchdown on the top");
            } else if (ovr == Override.NONE && mode == Mode.CORRECTIONS) {
                s.check(label + ": no walk-off after a landing that was made, forward held throughout (Corrections"
                                + " promises no landing here)",
                        !made || (r.standing && !r.fell), (made ? "touched down on the top at lane tick "
                                + r.touchedTopTick + "; " : "no touchdown on the top (a miss is allowed); ")
                                + r.standNote + "; " + parity);
                if (r.standing) {
                    s.check(label + ": a fresh forward press leaves the top within 10 ticks", r.leftTop,
                            r.leftTop ? "left after " + r.leftAt + " ticks"
                                    : "still on top 10 ticks after the fresh press");
                }
            } else if (ovr == Override.NONE) {
                s.check(label + ": still on the " + kind.landingClass + " top " + HOLD_TICKS
                                + " ticks after landing, forward held throughout",
                        r.standing && !r.fell, r.standNote + "; " + parity);
                s.check(label + ": a fresh forward press leaves the top within 10 ticks",
                        r.standing && r.leftTop, r.standing ? (r.leftTop ? "left after " + r.leftAt + " ticks"
                                : "still on top 10 ticks after the fresh press") : "did not stay on top first");
            } else {
                s.check(label + ": from the tick after sneak the assist writes nothing (effective input = raw keys)",
                        r.overrideTicksChecked >= 4 && r.overrideMismatches == 0,
                        Harness.fmt("sneak at flight tick %d, %d ticks compared, %d mismatches %s; %s",
                                r.overrideTick - r.jumpTick, r.overrideTicksChecked, r.overrideMismatches,
                                r.overrideFirst, parity));
            }
        });
    }

    /** One tick of a lane. Returns true when the lane run is over (stood 40 ticks, fell or timed out). */
    private static boolean laneTick(Scenario s, LaneRun r, int lz, Kind kind, double topY, double postCx,
                                    double postCz, Override ovr) {
        var p = H.p();
        var in = p.input;
        r.tick++;
        double z = p.getZ() - Arena.cz - OZ - lz;
        parityTick(r, p, z, postCx);
        {
            var ma = TravelBootstrap.movement();
            if (r.corrBefore < 0) {
                r.corrBefore = ma.corrections();
            }
            String st = ma.status();
            if (r.jumpTick >= 0 && !st.equals(r.lastStatus) && r.statuses.length() < 400) {
                r.statuses.append(r.tick - r.jumpTick).append(':').append(st).append(' ');
                r.lastStatus = st;
            }
            if (r.jumpTick >= 0) {
                r.maxZ = Math.max(r.maxZ, z);
                r.maxY = Math.max(r.maxY, p.getY() - Arena.FLOOR);
            }
        }
        if (r.jumpTick < 0 || r.takeoffTick < 0) {
            // the run-up: every change in what the assist writes (keys, sneak, view, script) with where the body is
            var brain = TravelBootstrap.movement().brain();
            String sig = Harness.fmt("f%s s%s%s%s yaw%.0f %s%s",
                    brain.forward() == com.utilitysuite.travel.movement.MovementAssistBrain.KEEP ? "-"
                            : String.valueOf(brain.forward()),
                    brain.strafe() == com.utilitysuite.travel.movement.MovementAssistBrain.KEEP ? "-"
                            : String.valueOf(brain.strafe()),
                    brain.sneak() ? " sneak" : "", brain.sprintOff() ? " nosprint" : "", p.getYRot(),
                    brain.commit().phase().name(), brain.scriptActive() ? "+" + brain.activeScript().name + "@"
                            + brain.scriptIndex() + "/" + brain.activeScript().takeoffIndex : "");
            if (!sig.equals(r.lastGround) && r.groundTrace.length() < 1400) {
                r.groundTrace.append(Harness.fmt(" [%d z%.2f x%+.2f %s]", r.tick, z, p.getX() - postCx, sig));
                r.lastGround = sig;
            }
        }
        if (r.landedTick < 0) {
            // a lane that times out says where the body was and what held it (test scaffolding: allocation is fine)
            r.standNote = Harness.fmt("never landed; last seen at lane tick %d: z %.2f, x %+.2f from the post centre,"
                            + " y %.2f, on ground %s, status '%s', forward %.1f, strafe %.1f, sneak %s, jump %s", r.tick,
                    z, p.getX() - postCx, p.getY() - Arena.FLOOR, p.onGround(), TravelBootstrap.movement().status(),
                    in.forwardImpulse, in.leftImpulse, in.shiftKeyDown, in.jumping);
        }
        if (p.getY() < Arena.FLOOR - 2) {
            r.fell = true;
            r.standNote = Harness.fmt("fell off, y %.2f at flight tick %d", p.getY() - Arena.FLOOR,
                    r.tick - Math.max(r.jumpTick, 0));
            r.standNote += Harness.fmt(" [jump at z %.2f (tick %d), max z %.2f, max y %.2f, post z %.1f, corrections %d, status %s]",
                    r.jumpZ, r.jumpTick, r.maxZ, r.maxY, postCz - Arena.cz - OZ - lz,
                    TravelBootstrap.movement().corrections() - r.corrBefore, r.statuses);
            return true;
        }
        // override phase: from the tick after sneak went down, effective input must equal the raw keys
        if (r.overrideTick >= 0 && r.tick > r.overrideTick && r.overrideTicksChecked < 6) {
            var o = H.mc().options;
            boolean same = in.up == o.keyUp.isDown() && in.down == o.keyDown.isDown()
                    && in.left == o.keyLeft.isDown() && in.right == o.keyRight.isDown()
                    && in.jumping == o.keyJump.isDown() && in.shiftKeyDown == o.keyShift.isDown();
            r.overrideTicksChecked++;
            if (!same) {
                r.overrideMismatches++;
                if (r.overrideFirst.isEmpty()) {
                    r.overrideFirst = Harness.fmt("(first at +%d: up %s/%s down %s/%s left %s/%s right %s/%s jump %s/%s"
                                    + " sneak %s/%s, input/raw)", r.tick - r.overrideTick, in.up, o.keyUp.isDown(),
                            in.down, o.keyDown.isDown(), in.left, o.keyLeft.isDown(), in.right,
                            o.keyRight.isDown(), in.jumping, o.keyJump.isDown(), in.shiftKeyDown,
                            o.keyShift.isDown());
                }
            }
        }
        // scripted input
        if (!r.touchedTop && p.onGround() && onTop(p, topY, postCx, postCz, kind.limit)) {
            r.touchedTop = true;
            r.touchedTopTick = r.tick;
        }
        // the planned press: at the trigger, once, also when the assist has already taken the jump (a press on the
        // post after an early assisted takeoff would be a second jump the "user" never meant)
        if (r.jumpTick < 0 && z >= 10.85) {
            H.physicalKey(GLFW.GLFW_KEY_SPACE, true);
            r.jumpTick = r.tick;
            r.jumpZ = z;
        } else if (r.jumpTick >= 0 && r.tick == r.jumpTick + 1) {
            H.physicalKey(GLFW.GLFW_KEY_SPACE, false);
        }
        if (r.jumpTick >= 0) {
            int k = r.tick - r.jumpTick;
            if (kind.corner && k == TAP_START) {
                H.physicalKey(GLFW.GLFW_KEY_A, true);
            } else if (kind.corner && k == TAP_START + TAP_LEN) {
                H.physicalKey(GLFW.GLFW_KEY_A, false);
            }
            if (ovr != Override.NONE && k == 4 && r.overrideTick < 0) {
                r.overrideTick = r.tick;
                if (ovr == Override.SCRIPTED) {
                    // automation sneaking mid-jump: a MOVEMENT lease taken only at that moment
                    r.lease = Services.arbiter().request(LeaseRequest.of("parkour_lane", ControlPriority.TRAVEL,
                            null, ControlChannel.MOVEMENT)).orElseThrow();
                    r.leaseRef[0] = r.lease;
                    Services.input().setKeyHeld(r.lease, SimKey.SNEAK, true);
                } else {
                    H.physicalKey(GLFW.GLFW_KEY_LEFT_SHIFT, true);
                }
            }
            if (ovr != Override.NONE && r.overrideTicksChecked >= 6) {
                if (r.lease != null) {
                    Services.input().setKeyHeld(r.lease, SimKey.SNEAK, false);
                }
                H.physicalKey(GLFW.GLFW_KEY_LEFT_SHIFT, false);
            }
        }
        if (r.landedTick < 0 && r.jumpTick >= 0 && r.tick > r.jumpTick + 2 && p.onGround() && z > 11.0) {
            r.landedTick = r.tick;
        }
        if (r.landedTick >= 0 && r.tick >= r.landedTick + HOLD_TICKS) {
            r.standing = onTop(p, topY, postCx, postCz, kind.limit);
            r.standNote = Harness.fmt("%d ticks after landing: feet y %.3f (top %.3f), axis distance to post centre"
                            + " %.3f (limit %.3f), onGround %s", HOLD_TICKS, p.getY(), topY,
                    Math.max(Math.abs(p.getX() - postCx), Math.abs(p.getZ() - postCz)), kind.limit, p.onGround());
            return true;
        }
        return false;
    }

    private static final int[] LANE_KEYS = {GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_SPACE,
            GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT_SHIFT};

    private static void releaseLaneKeys(LaneRun r, ControlLease[] leaseRef) {
        for (int key : LANE_KEYS) {
            H.physicalKey(key, false);
        }
        if (r.lease != null) {
            Services.input().releaseFor(r.lease);
            r.lease.close();
            r.lease = null;
            leaseRef[0] = null;
        }
    }

    /**
     * Replica parity for one lane tick. Lane steps run on {@code ClientTickEvent.Post} at LOWEST priority (Harness),
     * right after the player tick, so every value read here is the one the tick used or ended with:
     * <ul>
     *   <li>{@code input.forwardImpulse/leftImpulse/jumping/shiftKeyDown}: set by {@code input.tick} and the
     *       MovementInputUpdateEvent at the start of {@code LocalPlayer.aiStep}, read by {@code serverAiStep};
     *       nothing later in the tick writes them. Impulses, not the key flags, because the impulse is what moved
     *       the body;</li>
     *   <li>{@code isSprinting()}: start and stop are decided in {@code LocalPlayer.aiStep} before
     *       {@code super.aiStep()} (jump, travel); nothing after it in the tick changes the flag;</li>
     *   <li>{@code yRotO}: {@code LivingEntity.tick} sets it to the yaw after {@code aiStep}, i.e. the yaw travel and
     *       the jump boost used. {@code getYRot()} is not used: a later Post handler or a frame may have turned the
     *       camera already ({@code setYRot} leaves yRotO alone).</li>
     * </ul>
     * Three replicas: the drift replica (seeded once with the REAL velocity, re-seeded at takeoff), and the one-step
     * replica (the real state of the previous tick stepped once), which isolates rule errors from drift.
     */
    private static void parityTick(LaneRun r, net.minecraft.client.player.LocalPlayer p, double laneZ,
                                   double postCx) {
        var in = p.input;
        r.shapes.begin(p.level());
        int fwd = impulse(in.forwardImpulse);
        int str = impulse(in.leftImpulse);
        float yaw = p.yRotO;
        boolean sprint = p.isSprinting();
        boolean jump = in.jumping;
        boolean sneak = in.shiftKeyDown;
        if (!r.simReady) {
            capture(r.sim, p, 0);
            capture(r.prev, p, 0);
            r.simReady = true;
            r.parityPhase = p.onGround() ? 0 : 1;
            return;
        }
        // the one-step seed carries vanilla's noJumpDelay as it stood after the previous tick
        r.prev.jumpDelay = r.jumpDelay;
        boolean wasGround = r.prev.onGround;
        // LivingEntity.aiStep: count down, jump on the ground with delay 0 (then 10), no jump input resets it
        if (r.jumpDelay > 0) {
            r.jumpDelay--;
        }
        if (jump) {
            if (wasGround && r.jumpDelay == 0) {
                r.jumpDelay = 10;
            }
        } else {
            r.jumpDelay = 0;
        }
        r.step.copyFrom(r.prev);
        r.step.tick(fwd, str, yaw, sprint, jump, sneak, r.shapes);
        double stepErr = dist(r.step, p);
        r.maxStep = Math.max(r.maxStep, stepErr);
        if (stepErr > ONE_STEP_FLAG && r.firstStep.isEmpty()) {
            var v = p.getDeltaMovement();
            r.firstStep = Harness.fmt("first one-step error above %.0e at lane tick %d (jump key at %d): %.5f;"
                            + " inputs forward %d (impulse %.3f) strafe %d (impulse %.3f) yaw %.3f (yRot now %.3f)"
                            + " sprint %s jump %s sneak %s; before: onGround %s v (%.5f %.5f %.5f); real after:"
                            + " onGround %s v (%.5f %.5f %.5f) pos (%.5f %.5f %.5f); replica after: onGround %s"
                            + " v (%.5f %.5f %.5f) pos (%.5f %.5f %.5f)",
                    ONE_STEP_FLAG, r.tick, r.jumpTick, stepErr, fwd, in.forwardImpulse, str, in.leftImpulse, yaw,
                    p.getYRot(), sprint, jump, sneak, wasGround, r.prev.vx, r.prev.vy, r.prev.vz, p.onGround(),
                    v.x, v.y, v.z, p.getX(), p.getY(), p.getZ(), r.step.onGround, r.step.vx, r.step.vy, r.step.vz,
                    r.step.x, r.step.y, r.step.z);
        }
        r.sim.tick(fwd, str, yaw, sprint, jump, sneak, r.shapes);
        double err = dist(r.sim, p);
        if (r.parityPhase == 0) {
            if (wasGround && !p.onGround()) {
                // takeoff: the flight replica starts from the real state before this tick, stepped through it
                r.sim.copyFrom(r.step);
                err = stepErr;
                r.parityPhase = 1;
                r.takeoffTick = r.tick;
                r.takeoffZ = laneZ - (p.getZ() - r.prev.z);
                r.takeoffX = r.prev.x - postCx;
                r.takeoffSpeed = Math.sqrt(sq(p.getX() - r.prev.x) + sq(p.getZ() - r.prev.z));
                r.takeoffSprint = sprint;
                r.takeoffJump = jump;
            } else {
                r.maxGround = Math.max(r.maxGround, err);
            }
        }
        if (r.parityPhase == 1) {
            r.maxFlight = Math.max(r.maxFlight, err);
            commitPhase(r, r.tick - r.takeoffTick + 1);
            if (p.onGround()) {
                r.parityPhase = 2;
                r.touchdownTick = r.tick;
                r.touchdownX = p.getX() - postCx;
                r.touchdownZ = laneZ;
            }
        } else if (r.parityPhase == 2) {
            r.maxAfter = Math.max(r.maxAfter, err);
        }
        capture(r.prev, p, r.jumpDelay);
    }

    /** Records the commitment phase of this flight tick (run-length coded: "1-2 TAKEOFF, 3-9 FLIGHT"). */
    private static void commitPhase(LaneRun r, int flightTick) {
        var brain = TravelBootstrap.movement().brain();
        // the phase, plus the technique script being replayed (if any)
        String ph = brain.commit().phase().name() + (brain.scriptActive() ? "+" + brain.activeScript().name : "");
        if (!ph.equals(r.lastCommit)) {
            closeCommitRun(r, flightTick - 1);
            r.lastCommit = ph;
            r.commitFrom = flightTick;
        }
        if (brain.scriptActive()) {
            // how far the body is from the script's predicted line (the replay drops the script past 0.08)
            var sc = brain.activeScript();
            int i = brain.scriptIndex();
            var p = H.p();
            if (r.scriptInfo.isEmpty()) {
                r.scriptInfo = Harness.fmt("%s, takeoff index %d, land index %d, stop margin %.3f, view at the end"
                        + " %.1f", sc.name, sc.takeoffIndex, sc.landIndex, sc.margin,
                        sc.yaw[Math.max(0, Math.min(sc.landIndex, sc.length - 1))]);
            }
            if (i >= 0 && i < sc.length) {
                double d = Math.sqrt(sq(p.getX() - Arena.cx - OX - (sc.x[i] - Arena.cx - OX))
                        + sq(p.getY() - sc.y[i]) + sq(p.getZ() - sc.z[i]));
                if (d > r.scriptDrift) {
                    r.scriptDrift = d;
                    r.scriptDriftTick = flightTick;
                }
            }
        }
    }

    private static void closeCommitRun(LaneRun r, int lastTick) {
        if (r.commitFrom < 0) {
            return;
        }
        if (r.commitPhases.length() > 0) {
            r.commitPhases.append(", ");
        }
        r.commitPhases.append(r.commitFrom == lastTick ? Integer.toString(lastTick) : r.commitFrom + "-" + lastTick)
                .append(' ').append(r.lastCommit);
        r.commitFrom = -1;
    }

    /** The takeoff and flight record of a lane run, for its result note. */
    private static String flightNote(LaneRun r) {
        if (r.takeoffTick < 0) {
            return "no takeoff recorded";
        }
        int last = (r.touchdownTick >= 0 ? r.touchdownTick : r.tick) - r.takeoffTick + 1;
        StringBuilder phases = new StringBuilder(r.commitPhases);
        if (r.commitFrom >= 0) {
            phases.append(phases.length() > 0 ? ", " : "")
                    .append(r.commitFrom == last ? Integer.toString(last) : r.commitFrom + "-" + last)
                    .append(' ').append(r.lastCommit);
        }
        return Harness.fmt("takeoff at lane tick %d (jump key pressed after tick %d) from z %.3f, x %+.3f from the"
                        + " post centre, %.4f block/tick, sprint %s, jump %s; %s; commitment phase per flight tick:"
                        + " %s", r.takeoffTick, r.jumpTick, r.takeoffZ, r.takeoffX, r.takeoffSpeed, r.takeoffSprint,
                r.takeoffJump, r.touchdownTick >= 0 ? Harness.fmt("touchdown at flight tick %d, z %.3f, x %+.3f from"
                        + " the post centre", last, r.touchdownZ, r.touchdownX) : "no touchdown", phases)
                + (r.scriptInfo.isEmpty() ? "" : Harness.fmt("; script %s; largest distance from its line in flight"
                        + " %.3f (flight tick %d)", r.scriptInfo, r.scriptDrift, r.scriptDriftTick));
    }

    /** The replica error summary of a lane run. */
    private static String parityNote(LaneRun r) {
        return Harness.fmt("replica error: ground run %.6f, flight %.6f, after touchdown %s, one-step max %.2e%s",
                r.maxGround, r.maxFlight, r.touchdownTick >= 0 ? Harness.fmt("%.6f", r.maxAfter) : "not measured"
                        + " (no touchdown)", r.maxStep, r.firstStep.isEmpty() ? "" : "; " + r.firstStep);
    }

    /** The real body as a replica state: position, velocity, flags. */
    private static void capture(MovementPhysics m, net.minecraft.client.player.LocalPlayer p, int jumpDelay) {
        var v = p.getDeltaMovement();
        m.set(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.onGround());
        m.fallDistance = p.fallDistance;
        m.sprinting = p.isSprinting();
        m.horizontalCollision = p.horizontalCollision;
        // LocalPlayer.aiStep stops the sprint on the next tick after a wall contact that is not minor
        m.sprintBlocked = p.horizontalCollision && !p.minorHorizontalCollision;
        m.crouching = p.getPose() == net.minecraft.world.entity.Pose.CROUCHING;
        m.jumpDelay = jumpDelay;
    }

    private static double dist(MovementPhysics m, net.minecraft.client.player.LocalPlayer p) {
        return Math.sqrt(sq(m.x - p.getX()) + sq(m.y - p.getY()) + sq(m.z - p.getZ()));
    }

    /** The key direction of an input impulse (sneaking scales it to 0.3; the replica applies that itself). */
    private static int impulse(float f) {
        return f > 1.0E-5F ? 1 : f < -1.0E-5F ? -1 : 0;
    }

    private static boolean onTop(net.minecraft.world.entity.player.Player p, double topY, double cx, double cz,
                                 double limit) {
        return p.onGround() && Math.abs(p.getY() - topY) < 0.01
                && Math.max(Math.abs(p.getX() - cx), Math.abs(p.getZ() - cz)) < limit;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String laneFill(int lz, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return fillWorld(x0, y0, lz + z0, x1, y1, lz + z1, block);
    }

    private static String fillWorld(int x0,int y0, int z0, int x1, int y1, int z1, String block) {
        int bx = Arena.cx + OX;
        int by = Arena.FLOOR;
        int bz = Arena.cz + OZ;
        return "fill " + (bx + x0) + " " + (by + y0) + " " + (bz + z0) + " " + (bx + x1) + " " + (by + y1) + " "
                + (bz + z1) + " " + block;
    }
}
