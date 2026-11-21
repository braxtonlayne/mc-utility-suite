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
import com.utilitysuite.travel.movement.MovementAssist;
import com.utilitysuite.travel.movement.ParkourCourse;
import com.utilitysuite.travel.movement.ParkourRun;
import com.utilitysuite.travel.terrain.TerrainAssist;

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

    private static String fillWorld(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        int bx = Arena.cx + OX;
        int by = Arena.FLOOR;
        int bz = Arena.cz + OZ;
        return "fill " + (bx + x0) + " " + (by + y0) + " " + (bz + z0) + " " + (bx + x1) + " " + (by + y1) + " "
                + (bz + z1) + " " + block;
    }
}
