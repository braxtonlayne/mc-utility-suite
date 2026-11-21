package com.utilitysuite.devtest;

import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.PlanPreview;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.ui.UiBootstrap;

import java.util.List;
import java.util.Optional;

/** Scenario 4: goal engine + Baritone: plan preview, craft through the recipe book, gather coal. */
final class GoalScenario {

    private GoalScenario() {
    }

    static Optional<JobSnapshot> job(long id) {
        IGoalEngine g = Services.goals();
        Optional<JobSnapshot> a = g.activeJob();
        if (a.isPresent() && a.get().jobId() == id) {
            return a;
        }
        for (JobSnapshot s : g.queuedJobs()) {
            if (s.jobId() == id) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    static String describe(long id) {
        return job(id).map(s -> s.status() + " step='" + s.currentStep() + "' progress=" + s.progressCurrent() + "/"
                + s.progressTarget() + " reason='" + s.reason() + "' replans=" + s.replanCount()).orElse("job gone");
    }

    static boolean terminal(long id) {
        return job(id).map(s -> s.status().isTerminal()).orElse(true);
    }

    static Scenario build() {
        Scenario s = new Scenario("4-goal", "Goal engine + Baritone");
        IGoalEngine g = Services.goals();
        long[] jobId = {-1};
        int[] before = new int[1];
        double[][] start = new double[1][];
        String[] lastDesc = {""};
        boolean[] sawHud = new boolean[1];
        boolean[] sawMoving = new boolean[1];
        List<String> steps = new java.util.ArrayList<>();

        // --- plan preview on a fresh spawn (empty inventory) ---
        s.run("back to spawn", () -> Harness.command("spreadplayers " + (int) BootScenario.spawn[0] + " "
                + (int) BootScenario.spawn[2] + " 0 1 false @s"));
        s.waitTicks(60);
        s.cmd("clear @s");
        s.run("preview gather 1 diamond", () -> {
            ParseResult r = g.parse("gather 1 diamond");
            s.check("parse 'gather 1 diamond'", r.isOk(), r.isOk() ? r.request().toString() : r.error());
            g.requestPreview(r.request());
        });
        s.waitFor("preview ready", 200, () -> g.lastPreview().map(p -> p.request() instanceof GoalRequest.Gather ga
                && ga.itemId().equals("minecraft:diamond")).orElse(false));
        s.run("preview content", () -> {
            PlanPreview p = g.lastPreview().orElseThrow();
            String joined = String.join(" | ", p.steps()).toLowerCase();
            boolean chain = joined.contains("log") && joined.contains("crafting_table")
                    && joined.contains("pickaxe") && joined.contains("iron");
            s.check("preview shows the bootstrap chain (logs -> table -> pickaxes -> iron)", chain,
                    p.steps().size() + " steps: " + abbreviate(p.steps(), 12) + "; warnings " + p.warnings());
        });

        // --- craft 1 crafting_table from logs (recipe-book placement) ---
        s.cmd("recipe give @s *");
        s.cmd("give @s minecraft:oak_log 2");
        s.waitTicks(5);
        s.run("submit craft", () -> {
            before[0] = H.count("minecraft:crafting_table");
            ParseResult r = g.parse("craft 1 crafting_table");
            s.check("parse 'craft 1 crafting_table'", r.isOk(), r.isOk() ? r.request().label() : r.error());
            jobId[0] = g.submit(r.request());
        });
        s.step("craft job runs", t -> {
            lastDesc[0] = describe(jobId[0]);
            if (terminal(jobId[0])) {
                return true;
            }
            if (t > 20 * 60) {
                throw new Scenario.StepTimeout("craft job did not finish in 60 s: " + lastDesc[0]);
            }
            return false;
        });
        s.run("craft result", () -> {
            int now = H.count("minecraft:crafting_table");
            s.check("crafting table crafted via recipe book", now >= before[0] + 1,
                    "tables " + before[0] + " -> " + now + "; job: " + lastDesc[0] + "; planks="
                            + H.count("minecraft:oak_planks") + " logs=" + H.count("minecraft:oak_log"));
        });

        // --- gather 4 coal from nothing: the planner must bootstrap logs -> planks -> table -> pickaxe -> coal ---
        // Empty inventory and no unlocked recipes, like a fresh survival spawn; the only input is the goal text.
        s.run("back to spawn for coal", () -> Harness.command("spreadplayers " + (int) BootScenario.spawn[0] + " "
                + (int) BootScenario.spawn[2] + " 0 1 false @s"));
        s.waitTicks(40);
        s.cmd("clear @s");
        // Take recipes first, then revoke advancements: vanilla unlocks recipes through advancements (the crafting
        // table on the first tick, planks when a log is picked up), so this re-creates a fresh player's recipe book.
        s.cmd("recipe take @s *");
        s.cmd("advancement revoke @s everything");
        s.cmd("effect give @s minecraft:saturation 600 0 true");
        s.waitTicks(5);
        s.run("submit gather coal", () -> {
            before[0] = H.count("minecraft:coal");
            start[0] = H.pos();
            ParseResult r = g.parse("gather 4 coal");
            s.check("parse 'gather 4 coal'", r.isOk(), r.isOk() ? r.request().label() : r.error());
            jobId[0] = g.submit(r.request());
            s.check("Baritone available", Services.navigator().isAvailable(), "navigator available="
                    + Services.navigator().isAvailable());
        });
        // no head snapping: the fastest look change between two ticks while Baritone works, in degrees per real
        // second (the smoother's cap is time based: a slow frame may legitimately turn more than 22.5 deg per tick)
        float[] look = {Float.NaN, 0f, 0f};
        long[] lookNs = new long[1];
        int[] snapNotes = new int[1];
        int[] turnAt = new int[1];
        int[] lookEntity = {Integer.MIN_VALUE};
        s.step("gather job runs", t -> {
            String d = describe(jobId[0]);
            var lp = H.p();
            // a death/respawn swaps in a new player entity with a reset camera: not a turn, restart the baseline
            if (lp.getId() != lookEntity[0] || lp.isDeadOrDying()) {
                lookEntity[0] = lp.getId();
                look[0] = Float.NaN;
            }
            if (!Float.isNaN(look[0])) {
                float turn = (float) Math.hypot(net.minecraft.util.Mth.wrapDegrees(lp.getYRot() - look[0]),
                        lp.getXRot() - look[1]);
                double sec = Math.max(0.001, (System.nanoTime() - lookNs[0]) / 1.0e9);
                // the smoother turns per rendered frame: a tick window can hold up to one extra frame's turn (<= 50 ms)
                float dTurn = turn < 1f ? 0f : (float) (turn / (Math.max(sec, 0.05) + 0.05));
                if (dTurn > look[2]) {
                    look[2] = dTurn;
                    turnAt[0] = t;
                }
                if (dTurn > 450f && snapNotes[0]++ < 6) {
                    var sm = (com.utilitysuite.core.input.InputSmoother) Services.input();
                    s.note(Harness.fmt("fast turn t=%d: %.1f deg in %.0f ms (%.0f deg/s), yaw %.1f -> %.1f, pitch %.1f"
                                    + " -> %.1f, guard %s, foreign turns %d, camera holder %s, %s", t, turn,
                            sec * 1000, dTurn, look[0], lp.getYRot(), look[1], lp.getXRot(),
                            sm.isForeignRotationGuardOn(), sm.foreignTurns(),
                            Services.arbiter().holder(com.utilitysuite.core.api.ControlChannel.CAMERA)
                                    .map(l -> l.moduleId()).orElse("none"), d));
                }
            }
            lookNs[0] = System.nanoTime();
            look[0] = lp.getYRot();
            look[1] = lp.getXRot();
            job(jobId[0]).ifPresent(j -> {
                String st = j.currentStep();
                if (st != null && !st.isEmpty() && (steps.isEmpty() || !steps.get(steps.size() - 1).equals(st))) {
                    steps.add(st);
                }
            });
            if (!d.equals(lastDesc[0])) {
                s.note("t=" + t + " " + d + " at " + H.posStr());
                lastDesc[0] = d;
            }
            if (!sawHud[0] && !UiBootstrap.hud().text().isEmpty()) {
                sawHud[0] = true;
                s.check("HUD shows one status line while the job runs", true, "'" + UiBootstrap.hud().text() + "'");
            }
            if (t == 30) {
                Shots.clearChat();
            }
            if (t == 40) {
                Shots.take(s, "goal-gather-hud");
            }
            if (!sawMoving[0] && H.dist(start[0], H.pos()) > 2) {
                sawMoving[0] = true;
                s.check("Baritone pathing moves the player", true, "after " + t + " ticks, at " + H.posStr());
            }
            if (terminal(jobId[0])) {
                return true; // mined + walked back (Return/Deposit can take a while from deep in a cave)
            }
            if (job(jobId[0]).map(j -> j.status() == com.utilitysuite.core.api.JobStatus.PAUSED).orElse(false)
                    && t > 200) {
                s.note("job paused: " + d);
                return true;
            }
            if (t > 20 * 600) {
                throw new Scenario.StepTimeout("gather job did not finish in 600 s: " + d);
            }
            return false;
        });
        s.run("gather result", () -> {
            int delta = H.count("minecraft:coal") - before[0];
            if (!sawHud[0]) {
                s.check("HUD shows one status line while the job runs", false, "HUD text stayed empty");
            }
            if (!sawMoving[0]) {
                s.check("Baritone pathing moves the player", false, "player never moved > 2 blocks");
            }
            Optional<JobSnapshot> j = job(jobId[0]);
            String seq = String.join(" > ", steps).toLowerCase();
            s.note("step sequence: " + String.join(" > ", steps));
            s.check("no head snapping while Baritone works (fastest turn <= 500 deg/s with one frame of slack; cap 450)",
                    look[2] <= 500f, Harness.fmt("fastest turn %.0f deg/s at tick %d", look[2], turnAt[0]));
            s.check("started from an empty inventory and bootstrapped its own tools (wood + pickaxe steps)",
                    (seq.contains("wood") || seq.contains("log")) && seq.contains("pick"), "steps: " + seq);
            s.note("ends with: pickaxes " + H.count("minecraft:wooden_pickaxe") + " wooden / "
                    + H.count("minecraft:stone_pickaxe") + " stone, cobblestone " + H.count("minecraft:cobblestone"));
            s.check("gather 4 coal: inventory delta >= 4 and job completes", delta >= 4
                            && j.map(x -> x.status() == com.utilitysuite.core.api.JobStatus.COMPLETED).orElse(true),
                    "coal delta " + delta + "; job: " + describe(jobId[0]));
        });
        s.always("cancel jobs", () -> {
            for (JobSnapshot q : g.queuedJobs()) {
                g.cancel(q.jobId());
            }
            g.activeJob().ifPresent(a -> g.cancel(a.jobId()));
        });
        return s;
    }

    static String abbreviate(List<String> xs, int n) {
        if (xs.size() <= n) {
            return xs.toString();
        }
        return xs.subList(0, n) + " ... (+" + (xs.size() - n) + ")";
    }
}
