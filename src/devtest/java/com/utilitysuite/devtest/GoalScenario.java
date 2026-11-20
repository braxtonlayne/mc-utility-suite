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

        // --- gather 4 coal with Baritone ---
        s.cmd("give @s minecraft:iron_pickaxe");
        s.cmd("give @s minecraft:bread 16");
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
        s.step("gather job runs", t -> {
            String d = describe(jobId[0]);
            if (!d.equals(lastDesc[0])) {
                s.note("t=" + t + " " + d + " at " + H.posStr());
                lastDesc[0] = d;
            }
            if (!sawHud[0] && !UiBootstrap.hud().text().isEmpty()) {
                sawHud[0] = true;
                s.check("HUD shows one status line while the job runs", true, "'" + UiBootstrap.hud().text() + "'");
            }
            if (!sawMoving[0] && H.dist(start[0], H.pos()) > 2) {
                sawMoving[0] = true;
                s.check("Baritone pathing moves the player", true, "after " + t + " ticks, at " + H.posStr());
            }
            if (terminal(jobId[0]) || H.count("minecraft:coal") - before[0] >= 4 && t > 40) {
                return terminal(jobId[0]) || t > 400;
            }
            if (job(jobId[0]).map(j -> j.status() == com.utilitysuite.core.api.JobStatus.PAUSED).orElse(false)
                    && t > 200) {
                s.note("job paused: " + d);
                return true;
            }
            if (t > 20 * 240) {
                throw new Scenario.StepTimeout("gather job did not finish in 240 s: " + d);
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
