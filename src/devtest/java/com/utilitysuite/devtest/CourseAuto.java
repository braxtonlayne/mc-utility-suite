package com.utilitysuite.devtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.Set;

/**
 * The automated course runner (docs/design/parkour-benchmark-course.md, "What the automated runner needs"), driven
 * from {@link CourseLive} when {@code -Dutilitysuite.courseAuto=<configs>} is set: a comma list of
 * {@code mode[+alt]} (mode = off|corrections|copilot|takeover, or baritone: Baritone runs it as the pathfinding-bot benchmark, see {@link BaritoneBench}; {@code +alt} takes every shortcut), each run once per
 * seed 1..{@code utilitysuite.courseAutoSeeds} (default 1). When the list is done it writes
 * {@code run/course/auto-summary.txt} and quits the game.
 * <p>
 * Deliberately simple, so Movement Assist is the only clever thing in the loop: forward + sprint held on the physical
 * keys, the view turned toward the next route point by a hand-like mouse (real {@code MouseHandler} deltas, so the
 * assist's turn gain and manual-look detection see a player's mouse; no turning while an automation holds the
 * camera), one jump press per takeoff point with a seeded sloppiness offset. Never strafes, never sneaks.
 * <p>
 * A run gives up on a jump after {@link #MAX_FALLS} falls on it (it is teleported over from then on and reported as
 * skipped), counts 10 s without reaching the next point as a fall ("stuck"), and ends after {@link #MAX_RUN_TICKS}.
 * Test scaffolding: allocation is fine here.
 */
final class CourseAuto {

    static final double REACH = 0.6;
    static final int MAX_FALLS = 3;
    static final int STUCK_TICKS = 200;
    static final int MAX_RUN_TICKS = 20 * 60 * 6;
    /** Hand-like mouse: a share of the heading error per tick, capped. */
    static final double TURN_SHARE = 0.35;
    static final double TURN_MAX_DEG = 12.0;
    static final double TURN_DEADBAND = 0.4;
    static final int PRESS_TICKS = 2;

    record Config(String mode, boolean alt, long seed) {
        String label() {
            return mode + (alt ? "+alt" : "") + " seed " + seed;
        }
    }

    /** Per-jump tally of one run. */
    static final class JumpStat {
        int tries, falls, stuck;
        boolean skipped;
    }

    enum State { WAIT, SETTLE, RUN, DONE }

    private final CourseLive live;
    private final CourseFile course;
    private final List<Config> queue = new ArrayList<>();
    private final List<JsonObject> results = new ArrayList<>();
    private int next;
    private Config cfg;
    private State state = State.WAIT;
    private int stateTicks;

    private List<CourseFile.RoutePoint> line;
    private int idx;
    private boolean pressed;
    private int jumpHeld;
    private int sinceAdvance;
    /** Last tick's feet position, for the measured speed (cleared by a teleport). */
    private double prevX, prevZ;
    private boolean havePrev;
    /** Ground ticks standing still with forward held, and ticks left of a W re-tap. */
    private int still, retap;
    private int runTicks;
    private String lastJump;
    private final Map<String, JumpStat> stats = new LinkedHashMap<>();

    CourseAuto(CourseLive live, CourseFile course, String spec, int seeds) {
        this.live = live;
        this.course = course;
        for (String part : spec.split(",")) {
            String p = part.strip().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            boolean alt = p.endsWith("+alt");
            String mode = alt ? p.substring(0, p.length() - 4) : p;
            for (int s = 1; s <= seeds; s++) {
                queue.add(new Config(mode, alt, s));
            }
        }
    }

    // ---- hooks from CourseLive ------------------------------------------------------------------------------------

    /** Every READY tick, after the timer's event was handled. */
    void tick(Minecraft mc, CourseTimer timer, double x, double y, double z) {
        stateTicks++;
        switch (state) {
            case WAIT -> {
                keys(false);
                if (timer.state != CourseTimer.State.IDLE || stateTicks < 40) {
                    return;
                }
                if (next >= queue.size()) {
                    done(mc);
                    return;
                }
                cfg = queue.get(next++);
                live.setMode(cfg.mode());
                if (bot()) {
                    BaritoneBench.configure();
                }
                stopNav();
                live.toStart();
                line = course.line(cfg.alt() ? course.altAt.keySet() : Set.of());
                stats.clear();
                idx = 1;
                resetTarget();
                lastJump = null;
                runTicks = 0;
                go(State.SETTLE);
                PlaytestMod.LOG.info("[course-auto] run {}/{}: {}", next, queue.size(), cfg.label());
            }
            case SETTLE -> {
                if (stateTicks > 30) {
                    mc.options.pauseOnLostFocus = false;
                    go(State.RUN);
                }
            }
            case RUN -> {
                if (bot()) {
                    runBot(mc, timer, x, y, z);
                } else {
                    run(mc, timer, x, y, z);
                }
            }
            default -> {
            }
        }
    }

    /** The timer saw a fall; CourseLive already put the body back on checkpoint {@code k} (-1 = start). */
    void onFell(int k, CourseTimer timer, double x, double y, double z) {
        if (state != State.RUN) {
            return;
        }
        String j = blame();
        stat(j).falls++;
        PlaytestMod.LOG.info(String.format(Locale.ROOT,
                "[course-auto] fall on %s (target %d %s), fell at %.2f %.2f %.2f", j, idx,
                idx < line.size() ? line.get(idx).tag() : "-", x, y, z));
        respawnAt(k, j, timer);
    }

    /**
     * The jump a fall or a stuck reset counts against: the one the current target belongs to (on the run-up to a
     * takeoff that is the coming jump: walking off counts against it), else the last jump left from.
     */
    private String blame() {
        if (idx < line.size()) {
            CourseFile.RoutePoint t = line.get(idx);
            String tag = t.tag();
            if (t.jump() != null && !"run".equals(tag) && !"checkpoint".equals(tag) && !"finish".equals(tag)) {
                return t.jump().id();
            }
        }
        return lastJump != null ? lastJump : "run-up";
    }

    /** Ends the current run unfinished (recorded) and moves on to the next one. */
    private void abortRun(CourseTimer timer, String why) {
        stopNav();
        JsonObject o = new JsonObject();
        o.addProperty("at", LocalDateTime.now().toString());
        o.addProperty("runner", "auto");
        o.addProperty("mode", bot() ? "BARITONE" : live.runModeNow());
        o.addProperty("line", cfg.alt() ? "alt" : "main");
        o.addProperty("seed", cfg.seed());
        o.addProperty("finished", false);
        o.addProperty("aborted", why);
        o.addProperty("ticks", timer.ticks);
        o.addProperty("falls", timer.falls);
        decorate(o);
        results.add(o);
        CourseLive.append(o);
        PlaytestMod.LOG.info("[course-auto] run aborted ({}) at route point {}: {}", why, idx, o);
        keys(false);
        live.toStart();
        go(State.WAIT);
    }

    void onFinished(JsonObject o) {
        if (state != State.RUN || cfg == null) {
            return;
        }
        stopNav();
        o.addProperty("runner", "auto");
        if (bot()) {
            o.addProperty("mode", "BARITONE");
        }
        o.addProperty("line", cfg.alt() ? "alt" : "main");
        o.addProperty("seed", cfg.seed());
        o.addProperty("finished", true);
        decorate(o);
        results.add(o);
        keys(false);
        go(State.WAIT);
    }

    // ---- the run --------------------------------------------------------------------------------------------------

    private void run(Minecraft mc, CourseTimer timer, double x, double y, double z) {
        LocalPlayer p = mc.player;
        if (++runTicks > MAX_RUN_TICKS) {
            abortRun(timer, "timed out");
            return;
        }
        if (retap > 0) {
            retap--; // W stays up this tick (a re-tap)
        } else {
            keys(true);
        }
        double mvx = havePrev ? x - prevX : 0, mvz = havePrev ? z - prevZ : 0;
        prevX = x;
        prevZ = z;
        havePrev = true;
        // stopped on the ground with forward held (the landing hold latches a key that was down in the air until it is
        // released): let go of W for two ticks and press it again, as a player would
        if (retap == 0 && p.onGround() && Math.hypot(mvx, mvz) < 0.02 && mc.options.keyUp.isDown()) {
            if (++still >= 4) {
                still = 0;
                retap = 2;
                H.physicalKey(InputConstants.KEY_W, false);
            }
        } else {
            still = 0;
        }
        if (jumpHeld > 0 && --jumpHeld == 0) {
            H.physicalKey(InputConstants.KEY_SPACE, false);
        }
        if (idx >= line.size()) {
            return; // past the last point: forward into the finish volume
        }
        CourseFile.RoutePoint t = line.get(idx);
        CourseFile.RoutePoint prev = line.get(idx - 1);
        String tag = t.tag();

        // a jump given up on is teleported over as soon as the body reaches its takeoff
        if ("takeoff".equals(tag) && t.jump() != null && stat(t.jump().id()).skipped
                && Math.hypot(t.x() - x, t.z() - z) < 1.5 && p.onGround()) {
            skipFrom(idx);
            return;
        }


        double segX = t.x() - prev.x(), segZ = t.z() - prev.z();
        double segLen = Math.max(1e-6, Math.hypot(segX, segZ));
        double ux = segX / segLen, uz = segZ / segLen;
        double ahead = (t.x() - x) * ux + (t.z() - z) * uz; // distance left along the line
        double dist = Math.hypot(t.x() - x, t.z() - z);

        if ("takeoff".equals(tag) && !pressed) {
            // a key pressed now acts on the next tick's move: press when the body crosses the point during that
            // move, shifted by the offset (-2 = two ticks before the crossing tick, +1 = one tick after)
            int off = offset(idx);
            double v = mvx * ux + mvz * uz; // measured: deltaMovement is stored after ground friction
            // held at the edge (the assist stops the body short of the point): a player presses jump there
            boolean stalled = havePrev && v < 0.04 && ahead < 0.8 && sinceAdvance > 10;
            if (stalled || ahead / Math.max(0.05, v) <= 1 - off) {
                PlaytestMod.LOG.info(String.format(Locale.ROOT,
                        "[course-auto] press %s off %+d ahead %.2f v %.3f at %.2f %.2f %.2f ground %b sprint %b key %b food %d yawErr %.1f",
                        jumpOf(t), off, ahead, v, x, y, z, p.onGround(), p.isSprinting(), mc.options.keySprint.isDown(), p.getFoodData().getFoodLevel(),
                        Mth.wrapDegrees(yawTo(segX, segZ) - p.getYRot())));
                press();
            }
        }

        boolean reached;
        if ("takeoff".equals(tag)) {
            reached = ahead < 0; // passed along the line (a late press fires on that tick at the latest)
        } else if ("climb".equals(tag)) {
            double nextY = idx + 1 < line.size() ? line.get(idx + 1).y() : t.y();
            reached = y >= nextY - 0.2;
        } else {
            // a landing counts on the ground or at landing height (a one-tick touch on a single block is easy to
            // miss at the end of a tick); passing over it higher up in the air is not landing on it
            reached = (dist < REACH || ahead < 0) && (!"land".equals(tag) || p.onGround() || Math.abs(y - t.y()) < 0.3);
        }
        if (reached) {
            advance(t);
        } else if (++sinceAdvance > STUCK_TICKS) {
            String j = blame();
            stat(j).stuck++;
            PlaytestMod.LOG.info("[course-auto] stuck before route point {} ({} {}), back to the checkpoint", idx,
                    tag, j);
            int k = timer.respawnIndex();
            live.respawn(k);
            respawnAt(k, j, timer);
            return;
        }
        if (idx < line.size()) {
            look(mc, p, x, z);
        }
    }

    private void advance(CourseFile.RoutePoint t) {
        if ("takeoff".equals(t.tag())) {
            String j = jumpOf(t);
            lastJump = j;
            stat(j).tries++;
            if (!pressed) {
                press();
            }
        }
        idx++;
        pressed = false;
        sinceAdvance = 0;
    }

    private void press() {
        pressed = true;
        H.physicalKey(InputConstants.KEY_SPACE, true);
        jumpHeld = PRESS_TICKS;
    }

    /**
     * Seeded sloppiness per takeoff and try: -2 or -1 ticks (early) 30% each, on time 25%, one late 15%. Same seed,
     * same presses in every mode; a retry after a fall draws again.
     */
    private int offset(int routeIndex) {
        CourseFile.RoutePoint t = line.get(routeIndex);
        int tries = t.jump() == null ? 0 : stat(t.jump().id()).tries;
        double r = new SplittableRandom(cfg.seed() * 1_000_003L + routeIndex * 31L + tries).nextDouble();
        return r < 0.30 ? -2 : r < 0.60 ? -1 : r < 0.85 ? 0 : 1;
    }

    private void resetTarget() {
        havePrev = false;
        pressed = false;
        sinceAdvance = 0;
        if (jumpHeld > 0) {
            jumpHeld = 0;
            H.physicalKey(InputConstants.KEY_SPACE, false);
        }
    }

    /** After a fall or a stuck reset: target the point after the checkpoint (or the start); maybe give up a jump. */
    private void respawnAt(int k, String blamed, CourseTimer timer) {
        stopNav();
        JumpStat s = stat(blamed);
        if (s.skipped && s.falls + s.stuck >= 2 * MAX_FALLS) {
            abortRun(timer, "still falling on " + blamed + " after skipping it");
            return;
        }
        if (!s.skipped && s.falls + s.stuck >= MAX_FALLS) {
            s.skipped = true;
            PlaytestMod.LOG.info("[course-auto] giving up on {} after {} falls: teleported over from now on",
                    blamed, s.falls + s.stuck);
        }
        int at = 1;
        if (k >= 0) {
            CourseFile.Checkpoint cp = course.checkpoints.get(k);
            for (int i = 0; i < line.size(); i++) {
                CourseFile.RoutePoint r = line.get(i);
                if ("checkpoint".equals(r.tag()) && Math.abs(r.x() - cp.x()) < 0.01 && Math.abs(r.z() - cp.z()) < 0.01) {
                    at = i + 1;
                    break;
                }
            }
        }
        idx = Math.min(at, line.size() - 1);
        resetTarget();
    }

    /** Teleports from the takeoff at {@code i} to the jump's landing point, facing the point after it. */
    private void skipFrom(int i) {
        String j = jumpOf(line.get(i));
        int land = i + 1;
        while (land < line.size() - 1 && !"land".equals(line.get(land).tag())) {
            land++;
        }
        CourseFile.RoutePoint l = line.get(land);
        CourseFile.RoutePoint after = line.get(Math.min(land + 1, line.size() - 1));
        float yaw = yawTo(after.x() - l.x(), after.z() - l.z());
        live.tpRel(l.x(), l.y(), l.z(), yaw);
        lastJump = j;
        idx = Math.min(land + 1, line.size() - 1);
        resetTarget();
    }
    // ---- the Baritone benchmark (mode "baritone") -----------------------------------------------------------------

    static final String BOT_ID = "course_bench";
    /** Ticks a Baritone leg may take before it counts as stuck (it may walk around and recompute). */
    static final int BOT_STUCK_TICKS = 400;

    private com.utilitysuite.core.api.NavigationRun nav;
    /** Route index the current navigator goal is for (-1 = none issued). */
    private int navIdx = -1;
    /** Route index whose refused leg was logged already. */
    private int refusedIdx = -1;

    private boolean bot() {
        return cfg != null && "baritone".equals(cfg.mode());
    }

    private void stopNav() {
        if (nav != null) {
            nav.cancel();
            nav = null;
        }
        navIdx = -1;
    }

    private static boolean botGoal(String tag) {
        return "land".equals(tag) || "checkpoint".equals(tag) || "finish".equals(tag);
    }

    /**
     * Baritone runs the course leg by leg: each landing, checkpoint and the finish is a GoalBlock handed to the leased
     * navigator. The physical keys stay up and the view is Baritone's. A leg Baritone finds no path for, or does not
     * reach in {@link #BOT_STUCK_TICKS}, counts as stuck; after {@link #MAX_FALLS} the jump is teleported over, as for
     * the scripted runner.
     */
    private void runBot(Minecraft mc, CourseTimer timer, double x, double y, double z) {
        LocalPlayer p = mc.player;
        if (++runTicks > MAX_RUN_TICKS) {
            abortRun(timer, "timed out");
            return;
        }
        keys(false);
        while (idx < line.size() - 1 && !botGoal(line.get(idx).tag())) {
            idx++;
        }
        CourseFile.RoutePoint t = line.get(idx);
        String j = t.jump() == null ? null : t.jump().id();
        if ("land".equals(t.tag()) && j != null && stat(j).skipped) {
            stopNav();
            CourseFile.RoutePoint after = line.get(Math.min(idx + 1, line.size() - 1));
            live.tpRel(t.x(), t.y(), t.z(), yawTo(after.x() - t.x(), after.z() - t.z()));
            lastJump = j;
            idx = Math.min(idx + 1, line.size() - 1);
            sinceAdvance = 0;
            return;
        }
        double dist = Math.hypot(t.x() - x, t.z() - z);
        if (dist < REACH && p.onGround() && Math.abs(y - t.y()) < 0.3 && idx < line.size() - 1) {
            if (j != null) {
                lastJump = j;
            }
            idx++;
            sinceAdvance = 0;
            return; // the next leg is issued next tick
        }
        if (navIdx != idx || nav == null || !nav.isActive()) {
            // Baritone gave up on its own (no path, stopped short); a lease cut short (manual input, preemption) is not
            // its failure: the leg is simply issued again
            boolean failed = nav != null && navIdx == idx && !nav.isActive() && nav.endReason() == null
                    && nav.status() == com.utilitysuite.core.api.NavigationStatus.FAILED;
            if (failed) {
                botStuck(timer, "no path");
                return;
            }
            if (navIdx != idx && "land".equals(t.tag()) && j != null) {
                stat(j).tries++;
            }
            int bx = live.originX() + Mth.floor(t.x());
            int by = live.originY() + Mth.floor(t.y() + 0.01);
            int bz = live.originZ() + Mth.floor(t.z());
            nav = Services.navigator().goToLeased(BOT_ID, com.utilitysuite.core.api.ControlPriority.GOAL, bx, by, bz, 0)
                    .orElse(null);
            navIdx = idx;
            if (nav == null && refusedIdx != idx) {
                refusedIdx = idx; // once per leg (the arbiter pauses automation for a while after manual input)
                PlaytestMod.LOG.warn("[course-auto] baritone refused the leg to {} {} {}", bx, by, bz);
            }
        }
        if (++sinceAdvance > BOT_STUCK_TICKS) {
            botStuck(timer, "stuck");
        }
    }

    private void botStuck(CourseTimer timer, String why) {
        String j = blame();
        stat(j).stuck++;
        PlaytestMod.LOG.info("[course-auto] baritone {} before route point {} ({} {}), back to the checkpoint", why, idx,
                line.get(idx).tag(), j);
        int k = timer.respawnIndex();
        live.respawn(k);
        respawnAt(k, j, timer);
    }


    // ---- the hand-like mouse --------------------------------------------------------------------------------------

    private static Field fDX, fDY;
    private static Method mTurn;

    /** How far ahead along the route line the view aims (a runner looks down the line, not at their feet). */
    static final double LOOKAHEAD = 1.5;

    private void look(Minecraft mc, LocalPlayer p, double x, double z) {
        if (Services.arbiter().holder(ControlChannel.CAMERA).isPresent()) {
            return; // an automation is turning the camera: a player would let it
        }
        CourseFile.RoutePoint t = line.get(idx);
        CourseFile.RoutePoint prev = line.get(idx - 1);
        double ax, az;
        if ("via".equals(t.tag())) {
            ax = t.x(); // steer through it
            az = t.z();
        } else {
            // the point LOOKAHEAD past the body's foot point on the line prev -> t, continuing on t -> next
            double sx = t.x() - prev.x(), sz = t.z() - prev.z();
            double len = Math.hypot(sx, sz);
            double along = len < 1e-6 ? 0 : ((x - prev.x()) * sx + (z - prev.z()) * sz) / len;
            double s = Math.max(0, along) + LOOKAHEAD;
            if (s <= len || idx + 1 >= line.size()) {
                double k = len < 1e-6 ? 0 : Math.min(s, len) / len;
                ax = prev.x() + sx * k;
                az = prev.z() + sz * k;
            } else {
                CourseFile.RoutePoint n = line.get(idx + 1);
                double nx = n.x() - t.x(), nz = n.z() - t.z();
                double nlen = Math.hypot(nx, nz);
                double k = nlen < 1e-6 ? 0 : Math.min(s - len, nlen) / nlen;
                ax = t.x() + nx * k;
                az = t.z() + nz * k;
            }
        }
        double dx = ax - x, dz = az - z;
        if (dx * dx + dz * dz < 0.04) {
            return;
        }
        double err = Mth.wrapDegrees(yawTo(dx, dz) - p.getYRot());
        if (Math.abs(err) < TURN_DEADBAND) {
            return;
        }
        double deg = Mth.clamp(err * TURN_SHARE, -TURN_MAX_DEG, TURN_MAX_DEG);
        double pitchErr = 8.0 - p.getXRot(); // a runner's view: slightly down
        double pitchDeg = Mth.clamp(pitchErr * TURN_SHARE, -TURN_MAX_DEG, TURN_MAX_DEG);
        mouse(mc, deg, Math.abs(pitchErr) < TURN_DEADBAND ? 0 : pitchDeg);
    }

    /** Moves the real mouse path by this many degrees (yaw, pitch) at the current sensitivity. */
    private static void mouse(Minecraft mc, double yawDeg, double pitchDeg) {
        try {
            if (mTurn == null) {
                fDX = MouseHandler.class.getDeclaredField("accumulatedDX");
                fDY = MouseHandler.class.getDeclaredField("accumulatedDY");
                mTurn = MouseHandler.class.getDeclaredMethod("turnPlayer", double.class);
                fDX.setAccessible(true);
                fDY.setAccessible(true);
                mTurn.setAccessible(true);
            }
            double s = mc.options.sensitivity().get() * 0.6 + 0.2;
            double unitsPerPx = s * s * s * 8.0; // turnPlayer, no spyglass, no smooth camera
            double pxX = yawDeg / 0.15 / unitsPerPx;
            double pxY = pitchDeg / 0.15 / unitsPerPx * (mc.options.invertMouseY().get() ? -1 : 1);
            MouseHandler m = mc.mouseHandler;
            fDX.setDouble(m, pxX);
            fDY.setDouble(m, pxY);
            mTurn.invoke(m, 0.05);
            fDX.setDouble(m, 0.0);
            fDY.setDouble(m, 0.0);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("course runner cannot reach the mouse path", ex);
        }
    }

    /** Minecraft yaw facing the horizontal direction (dx, dz). */
    static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ---- bookkeeping ----------------------------------------------------------------------------------------------

    private void keys(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (on == mc.options.keyUp.isDown() && on == mc.options.keySprint.isDown()) {
            return;
        }
        H.physicalKey(InputConstants.KEY_W, on);
        H.physicalKey(InputConstants.KEY_LCONTROL, on);
        if (!on && jumpHeld > 0) {
            jumpHeld = 0;
            H.physicalKey(InputConstants.KEY_SPACE, false);
        }
    }

    private static String jumpOf(CourseFile.RoutePoint t) {
        return t.jump() == null ? "run-up" : t.jump().id();
    }

    private JumpStat stat(String jump) {
        return stats.computeIfAbsent(jump == null ? "run-up" : jump, k -> new JumpStat());
    }

    private void decorate(JsonObject o) {
        JsonArray js = new JsonArray();
        List<String> skipped = new ArrayList<>();
        for (var e : stats.entrySet()) {
            JumpStat s = e.getValue();
            JsonObject j = new JsonObject();
            j.addProperty("id", e.getKey());
            j.addProperty("tries", s.tries);
            j.addProperty("falls", s.falls);
            j.addProperty("stuck", s.stuck);
            j.addProperty("skipped", s.skipped);
            js.add(j);
            if (s.skipped) {
                skipped.add(e.getKey());
            }
        }
        o.add("jumps", js);
        o.addProperty("skipped", String.join(" ", skipped));
    }

    private void go(State s) {
        state = s;
        stateTicks = 0;
    }

    private void done(Minecraft mc) {
        go(State.DONE);
        StringBuilder sb = new StringBuilder("course auto runs ").append(LocalDateTime.now()).append('\n');
        for (JsonObject o : results) {
            sb.append(String.format("%-12s %-4s seed %d  %s  %s  falls %d  skipped [%s]%n", o.get("mode").getAsString(),
                    o.get("line").getAsString(), o.get("seed").getAsLong(),
                    o.get("finished").getAsBoolean() ? "finished" : "TIMED OUT",
                    CourseTimer.clock(o.get("ticks").getAsInt()), o.get("falls").getAsInt(),
                    o.get("skipped").getAsString()));
            for (var je : o.getAsJsonArray("jumps")) {
                JsonObject j = je.getAsJsonObject();
                if (j.get("falls").getAsInt() + j.get("stuck").getAsInt() > 0) {
                    sb.append(String.format("    %-8s tries %d falls %d stuck %d%s%n", j.get("id").getAsString(),
                            j.get("tries").getAsInt(), j.get("falls").getAsInt(), j.get("stuck").getAsInt(),
                            j.get("skipped").getAsBoolean() ? " SKIPPED" : ""));
                }
            }
        }
        try {
            Path f = CourseLive.resultsFile().resolveSibling("auto-summary.txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            PlaytestMod.LOG.error("[course-auto] could not write the summary", ex);
        }
        PlaytestMod.LOG.info("[course-auto] ALL DONE\n{}", sb);
        mc.stop();
    }
}
