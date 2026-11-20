package com.utilitysuite.devtest;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.Services;

/** Scenario 5: Survival Autopilot preempts a running goal job to eat, then the job resumes. */
final class SurvivalScenario {

    private SurvivalScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("5-survival", "Survival autopilot eats, goal job resumes");
        IGoalEngine g = Services.goals();
        long[] jobId = {-1};
        int[] food = new int[2];
        boolean[] sawEatLease = new boolean[1];
        boolean[] sawPaused = new boolean[1];
        s.run("enable survival autopilot", () -> H.enable("survival_autopilot", true));
        s.cmd("give @s minecraft:bread 8");
        s.cmd("effect clear @s");
        s.run("submit long goal job", () -> {
            ParseResult r = g.parse("gather 64 dirt");
            s.check("parse 'gather 64 dirt'", r.isOk(), r.isOk() ? r.request().label() : r.error());
            jobId[0] = g.submit(r.request());
        });
        s.waitFor("job running", 400, () -> GoalScenario.job(jobId[0]).map(j -> j.status() == JobStatus.RUNNING)
                .orElse(false));
        s.cmd("effect give @s minecraft:hunger 60 255 true");
        s.waitFor("food drops to 12 or less", 20 * 60, () -> H.p().getFoodData().getFoodLevel() <= 12);
        s.cmd("effect clear @s minecraft:hunger");
        s.run("record food", () -> {
            food[0] = H.p().getFoodData().getFoodLevel();
            s.note("food " + food[0] + "; job " + GoalScenario.describe(jobId[0]));
        });
        s.step("autopilot eats", t -> {
            ControlLease h = Services.arbiter().holder(ControlChannel.HOTBAR).orElse(null);
            if (h != null && "survival_autopilot".equals(h.moduleId())) {
                sawEatLease[0] = true;
            }
            JobStatus st = GoalScenario.job(jobId[0]).map(JobSnapshot::status).orElse(null);
            if (st == JobStatus.PAUSED) {
                sawPaused[0] = true;
            }
            if (H.p().getFoodData().getFoodLevel() > food[0]) {
                return true;
            }
            if (t > 20 * 20) {
                throw new Scenario.StepTimeout("did not eat within 20 s: food " + H.p().getFoodData().getFoodLevel()
                        + ", hotbar holder " + (h == null ? "none" : h.moduleId()) + ", job "
                        + GoalScenario.describe(jobId[0]));
            }
            return false;
        });
        s.run("ate", () -> {
            food[1] = H.p().getFoodData().getFoodLevel();
            s.check("autopilot ate (food rose)", food[1] > food[0], "food " + food[0] + " -> " + food[1]
                    + ", bread left " + H.count("minecraft:bread"));
            s.check("eating took the SURVIVAL lease (preempting GOAL)", sawEatLease[0] || sawPaused[0],
                    "sawEatLease=" + sawEatLease[0] + " jobPausedSeen=" + sawPaused[0]);
        });
        s.waitUpTo("job resumes", 20 * 15, () -> GoalScenario.job(jobId[0]).map(j -> j.status() == JobStatus.RUNNING
                || j.status().isTerminal()).orElse(true));
        s.run("resumed", () -> {
            String d = GoalScenario.describe(jobId[0]);
            s.check("goal job resumes after eating", d.startsWith("RUNNING") || d.startsWith("COMPLETED"), d);
        });
        s.always("cleanup", () -> {
            if (jobId[0] >= 0) {
                g.cancel(jobId[0]);
            }
            H.enable("survival_autopilot", false);
        });
        s.cmd("effect clear @s");
        s.cmd("effect give @s minecraft:saturation 3 10 true");
        return s;
    }
}
