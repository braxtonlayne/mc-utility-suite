package com.utilitysuite.devtest;

import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.travel.movement.MovementAssistBrain;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only tick trace of the assist around the course's narrow and blocked landings (fence, wall and pane tops, the
 * corner and neo jumps): body, user keys, the estimator's pick, whether the line counts as blocked, the technique
 * script and the view requests. Zones come from each traced jump's takeoff and landing route points, 3 blocks wider
 * each way. Appends to {@code run/course/jump-trace.log}; one block per pass through a zone.
 */
final class JumpTrace {

    /** Techniques traced (substring of the course file's technique name). */
    private static final String[] TRACED = {"fence", "wall", "pane", "neo", "corner"};
    private static final double PAD = 3.0;
    private static final List<double[]> zones = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static int inside = -1;
    private static int tick;
    private static final double[] raw = new double[5];

    static void init(CourseFile course) {
        zones.clear();
        names.clear();
        double[] box = null;
        CourseFile.Jump at = null;
        for (CourseFile.RoutePoint p : course.route) {
            CourseFile.Jump j = p.jump();
            if (j == null || !traced(j.technique())) {
                continue;
            }
            if (j != at) {
                at = j;
                box = new double[] {p.x(), p.y(), p.z(), p.x(), p.y(), p.z()};
                zones.add(box);
                names.add(j.id() + " " + j.technique());
            }
            box[0] = Math.min(box[0], p.x());
            box[1] = Math.min(box[1], p.y());
            box[2] = Math.min(box[2], p.z());
            box[3] = Math.max(box[3], p.x());
            box[4] = Math.max(box[4], p.y());
            box[5] = Math.max(box[5], p.z());
        }
    }

    private static boolean traced(String technique) {
        for (String t : TRACED) {
            if (technique.contains(t)) {
                return true;
            }
        }
        return false;
    }

    static void tick(Minecraft mc, double x, double y, double z) {
        int in = -1;
        for (int i = 0; i < zones.size() && in < 0; i++) {
            double[] b = zones.get(i);
            if (x >= b[0] - PAD && x <= b[3] + PAD && z >= b[2] - PAD && z <= b[5] + PAD && y >= b[1] - 2
                    && y <= b[4] + 3) {
                in = i;
            }
        }
        if (in != inside && inside >= 0) {
            write("---- left the zone at " + fmt(x) + " " + fmt(y) + " " + fmt(z) + "\n");
        }
        if (in < 0) {
            inside = -1;
            return;
        }
        if (in != inside) {
            tick = 0;
            write("==== entered " + names.get(in) + "\n");
        }
        inside = in;
        var p = mc.player;
        MovementAssistBrain b = TravelBootstrap.movement().brain();
        StringBuilder s = new StringBuilder(256);
        s.append(tick++).append(" pos ").append(fmt(x)).append(' ').append(fmt(y)).append(' ').append(fmt(z))
                .append(" v ").append(fmt(p.getDeltaMovement().x)).append(' ').append(fmt(p.getDeltaMovement().z))
                .append(p.onGround() ? " G" : " A").append(p.isSprinting() ? " spr" : "")
                .append(" yaw ").append(fmt(p.getYRot())).append(" pitch ").append(fmt(p.getXRot()))
                .append(" keys f").append(fmt(p.input.forwardImpulse)).append(" s").append(fmt(p.input.leftImpulse))
                .append(p.input.jumping ? " J" : "").append(p.input.shiftKeyDown ? " SH" : "");
        if (b.rawTarget(raw)) {
            s.append(" raw ").append((int) raw[0]).append(',').append((int) raw[1]).append(',').append((int) raw[2])
                    .append(" c").append(fmt(raw[3])).append(" look").append(fmt(raw[4]));
        } else {
            s.append(" raw -");
        }
        s.append(b.intentActive() ? " tgt " + b.targetX() + "," + b.targetY() + "," + b.targetZ() : " tgt -")
                .append(b.targetFrozen() ? " frozen" : "")
                .append(b.techniqueFirst() ? " BLOCKED" : "")
                .append(" phase ").append(b.commit().phase().name());
        if (b.scriptActive()) {
            s.append(" script ").append(b.activeScript().name).append('@').append(b.scriptIndex()).append('/')
                    .append(b.activeScript().takeoffIndex).append(b.activeScript().usesYaw ? " yaw" : "");
            // the script's own keys and predicted position after this tick: tells a planned coast from a limiter
            // hold-back, and the next line's pos minus this "exp" is the open-loop drift
            com.utilitysuite.travel.movement.FlightScript sc = b.activeScript();
            int i = b.scriptIndex();
            if (i >= 0 && i < sc.length) {
                s.append(" plan f").append(sc.forward[i]).append(" s").append(sc.strafe[i])
                        .append(sc.sprint[i] ? " spr" : "").append(sc.jump[i] ? " J" : "")
                        .append(" exp ").append(fmt(sc.x[i] - (p.getX() - x))).append(' ')
                        .append(fmt(sc.y[i] - (p.getY() - y))).append(' ').append(fmt(sc.z[i] - (p.getZ() - z))).append(" land@").append(sc.landIndex);
            }
        }
        if (b.wantsCoSteer()) {
            s.append(" costeer ").append(fmt(b.coSteerYaw()));
        }
        s.append(" out f").append(b.forward() == MovementAssistBrain.KEEP ? "-" : String.valueOf(b.forward()))
                .append(" s").append(b.strafe() == MovementAssistBrain.KEEP ? "-" : String.valueOf(b.strafe()))
                .append(b.jump() ? " jump" : "").append(b.sneak() ? " sneak" : "").append('\n');
        write(s.toString());
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    private static void write(String line) {
        try {
            Path f = CourseLive.resultsFile().resolveSibling("jump-trace.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    private JumpTrace() {
    }
}
