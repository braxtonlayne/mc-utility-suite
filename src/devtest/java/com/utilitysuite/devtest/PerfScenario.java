package com.utilitysuite.devtest;

import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.Services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario 11: client tick / frame time and allocation rate with every module off vs a stress load (all overlays,
 * radar, OreSim, hazards, breadcrumbs, coach + a running goal job). Spark (client-side {@code /sparkc}) is used for
 * tps/health and a profile saved to a local file with {@code --save-to-file}; nothing is uploaded.
 */
final class PerfScenario {

    static final int WINDOW_TICKS = 20 * 30;
    static final String[] STRESS = {"world_overlays", "world_radar", "seed_oresim", "terrain_assist", "waypoints",
            "fight_coach", "world_knowledge", "goal_engine", "suite_hud", "smart_inventory"};

    private PerfScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("11-perf", "Performance: baseline vs stress (spark, saved to file)");
        Harness h = Harness.instance;
        Map<String, Boolean> before = new LinkedHashMap<>();
        IGoalEngine g = Services.goals();
        long[] job = {-1};
        Perf.Window[] w = new Perf.Window[2];
        List<String> sparkFiles = new ArrayList<>();
        long[] since = new long[1];

        s.run("back to spawn", () -> Harness.command("spreadplayers " + (int) BootScenario.spawn[0] + " "
                + (int) BootScenario.spawn[2] + " 0 1 false @s"));
        s.waitTicks(100);
        s.run("everything off", () -> {
            for (IModule m : Services.modules().all()) {
                before.put(m.id(), m.isEnabled());
                if (m.isEnabled()) {
                    H.enable(m.id(), false);
                }
            }
            ChatCapture.drain();
        });
        s.run("mark time", () -> since[0] = System.currentTimeMillis());
        s.cmd("sparkc profiler start");
        s.waitTicks(40);
        s.run("baseline window start", () -> {
            h.perf.start();
            s.note("baseline window starts at epoch ms " + System.currentTimeMillis());
        });
        s.waitTicks(WINDOW_TICKS);
        s.run("baseline window end", () -> {
            w[0] = h.perf.stop("baseline (all modules off)");
            h.perfWindows.add(w[0]);
        });
        s.cmd("sparkc tps");
        s.cmd("sparkc health");
        s.cmd("sparkc profiler stop --save-to-file");
        s.waitTicks(200);
        s.run("baseline spark", () -> collectSpark(s, "baseline", sparkFiles, since[0]));
        s.run("stress on", () -> {
            H.setting("world_overlays", "caves", true);
            H.setting("world_overlays", "fluids", true);
            H.setting("seed_oresim", "resources", "diamond,iron,gold,emerald,ancient_debris");
            H.setting("terrain_assist", "hazards", true);
            H.setting("waypoints", "show_breadcrumbs", true);
            for (String id : STRESS) {
                H.enable(id, true);
            }
            Harness.command("give @s minecraft:iron_pickaxe");
            ParseResult r = g.parse("gather 64 dirt");
            job[0] = g.submit(r.request());
        });
        s.waitUpTo("job running", 200, () -> g.activeJob().map(j -> j.jobId() == job[0]).orElse(false));
        s.run("mark time", () -> since[0] = System.currentTimeMillis());
        s.cmd("sparkc profiler start");
        s.waitTicks(40);
        s.run("stress window start", () -> {
            h.perf.start();
            s.note("stress window starts at epoch ms " + System.currentTimeMillis());
        });
        s.waitTicks(WINDOW_TICKS);
        s.run("stress window end", () -> {
            w[1] = h.perf.stop("stress (overlays+radar+OreSim+hazards+coach+goal job)");
            h.perfWindows.add(w[1]);
            s.note("goal job during stress: " + GoalScenario.describe(job[0]));
        });
        s.cmd("sparkc tps");
        s.cmd("sparkc health");
        s.cmd("sparkc profiler stop --save-to-file");
        s.waitTicks(200);
        s.run("stress spark", () -> collectSpark(s, "stress", sparkFiles, since[0]));
        s.run("report", () -> {
            for (Perf.Window x : w) {
                h.perfLines.add(x.summary());
                h.perfLines.add("  module onTick us/tick (" + x.label + "): " + fmtMap(x.moduleUsPerTick));
                h.perfLines.add("  allocation by thread: " + String.join(", ", x.topAlloc(6)));
                s.note(x.summary());
            }
            for (String f : sparkFiles) {
                h.perfLines.add("spark profile saved: " + f);
            }
            double dTick = w[1].tickMeanMs - w[0].tickMeanMs;
            double dFrame = w[1].frameMeanMs - w[0].frameMeanMs;
            double renderAlloc0 = w[0].allocBytesPerSec.getOrDefault("Render thread", 0.0);
            double renderAlloc1 = w[1].allocBytesPerSec.getOrDefault("Render thread", 0.0);
            String ev = Harness.fmt("client tick mean %.3f -> %.3f ms (+%.3f), frame mean %.2f -> %.2f ms (+%.2f), "
                            + "render-thread alloc %.2f -> %.2f MB/s", w[0].tickMeanMs, w[1].tickMeanMs, dTick,
                    w[0].frameMeanMs, w[1].frameMeanMs, dFrame, renderAlloc0 / 1e6, renderAlloc1 / 1e6);
            h.perfLines.add(ev);
            s.check("stress adds < 1 ms mean client tick", dTick < 1.0, ev);
            double modTotal = 0;
            for (double v : w[1].moduleUsPerTick.values()) {
                modTotal += v;
            }
            s.check("all module onTick under stress < 500 us/tick", modTotal < 500,
                    Harness.fmt("%.1f us/tick total: %s", modTotal, fmtMap(w[1].moduleUsPerTick)));
            s.check("spark profiles saved to local files (no upload)", sparkFiles.size() == 2,
                    sparkFiles.toString());
        });
        s.always("restore", () -> {
            if (job[0] >= 0) {
                g.cancel(job[0]);
            }
            for (JobSnapshot q : g.queuedJobs()) {
                g.cancel(q.jobId());
            }
            for (Map.Entry<String, Boolean> e : before.entrySet()) {
                H.enable(e.getKey(), e.getValue());
            }
        });
        return s;
    }

    /**
     * Spark's "data has been written to" line does not reach {@link ChatCapture} (spark's worker thread adds it to the
     * chat component directly), so the saved profile is found by listing {@code config/spark} for files newer than
     * the profiler start.
     */
    private static void collectSpark(Scenario s, String label, List<String> files, long sinceMillis) {
        for (String line : ChatCapture.drain()) {
            s.note("spark " + label + ": " + line);
            if (line.contains("spark.lucko.me") || line.contains("bytebin")) {
                s.check("spark did not upload", false, line);
            }
        }
        java.io.File dir = new java.io.File(H.mc().gameDirectory, "config/spark");
        java.io.File[] found = dir.listFiles((d, n) -> n.endsWith(".sparkprofile"));
        if (found == null) {
            return;
        }
        for (java.io.File f : found) {
            String path = f.getAbsolutePath();
            if (f.lastModified() >= sinceMillis && !files.contains(path)) {
                files.add(path);
                s.note("spark " + label + " profile: " + path + " (" + f.length() / 1024 + " KiB)");
            }
        }
    }

    static String fmtMap(Map<String, Double> m) {
        StringBuilder sb = new StringBuilder();
        m.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e ->
                sb.append(e.getKey()).append(' ').append(Harness.fmt("%.1f", e.getValue())).append(", "));
        return sb.length() == 0 ? "-" : sb.substring(0, sb.length() - 2);
    }
}
