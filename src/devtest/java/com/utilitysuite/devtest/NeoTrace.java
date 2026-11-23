package com.utilitysuite.devtest;

import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.travel.movement.MovementAssistBrain;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Dev-only tick trace of the assist around the course's J36 neo (relative x 30..37, z -8..1): body, user keys, the
 * estimator's pick, whether the line counts as blocked, the technique script and the view requests. Appends to
 * {@code run/course/neo-trace.log}; one block per pass through the zone.
 */
final class NeoTrace {

    private static boolean inside;
    private static int tick;
    private static final double[] raw = new double[5];

    static void tick(Minecraft mc, double x, double y, double z) {
        boolean in = x >= 30 && x <= 37 && z >= -8 && z <= 1 && y >= -1 && y <= 4;
        if (!in) {
            if (inside) {
                write("---- left the zone at " + fmt(x) + " " + fmt(y) + " " + fmt(z) + "\n");
            }
            inside = false;
            return;
        }
        if (!inside) {
            tick = 0;
            write("==== entered J36 zone\n");
        }
        inside = true;
        var p = mc.player;
        MovementAssistBrain b = TravelBootstrap.movement().brain();
        StringBuilder s = new StringBuilder(256);
        s.append(tick++).append(" pos ").append(fmt(x)).append(' ').append(fmt(y)).append(' ').append(fmt(z))
                .append(" v ").append(fmt(p.getDeltaMovement().x)).append(' ').append(fmt(p.getDeltaMovement().z))
                .append(p.onGround() ? " G" : " A")
                .append(" yaw ").append(fmt(p.getYRot())).append(" pitch ").append(fmt(p.getXRot()))
                .append(" keys f").append(fmt(p.input.forwardImpulse)).append(" s").append(fmt(p.input.leftImpulse))
                .append(p.input.jumping ? " J" : "");
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
            Path f = CourseLive.resultsFile().resolveSibling("neo-trace.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    private NeoTrace() {
    }
}
