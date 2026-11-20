package com.utilitysuite.devtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/** Renders the playtest results as Markdown and JSON. */
final class Report {

    private Report() {
    }

    static String markdown(Harness h, List<Scenario> all, String outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Utility Suite playtest report\n\n");
        sb.append("- Generated: ").append(Harness.now()).append('\n');
        sb.append("- Outcome: ").append(outcome).append('\n');
        sb.append("- World: `").append(Harness.WORLD).append("`, seed ").append(Harness.SEED)
                .append(", survival, cheats on\n");
        if (h.worldReadyMillis > 0) {
            sb.append("- Launch to world ready: ")
                    .append(Harness.fmt("%.1f s", (h.worldReadyMillis - h.launchMillis) / 1000.0)).append('\n');
        }
        sb.append("\n## Summary\n\n| Scenario | Result | Checks | Time |\n|---|---|---|---|\n");
        for (Scenario s : all) {
            int pass = 0;
            int fail = 0;
            int nt = 0;
            for (Scenario.Check c : s.checks) {
                if (c.notTested()) {
                    nt++;
                } else if (c.pass()) {
                    pass++;
                } else {
                    fail++;
                }
            }
            double secs = s.endNanos > s.startNanos ? (s.endNanos - s.startNanos) / 1e9 : 0;
            sb.append("| ").append(s.id).append(' ').append(s.title).append(" | **").append(s.status()).append("** | ")
                    .append(pass).append(" pass / ").append(fail).append(" fail / ").append(nt).append(" n/t | ")
                    .append(Harness.fmt("%.1f s (%d ticks)", secs, s.ticks)).append(" |\n");
        }
        if (!h.perfLines.isEmpty()) {
            sb.append("\n## Performance\n\n");
            for (String l : h.perfLines) {
                sb.append("- ").append(l).append('\n');
            }
        }
        sb.append("\n## Details\n");
        for (Scenario s : all) {
            sb.append("\n### ").append(s.id).append(' ').append(s.title).append(" — ").append(s.status()).append("\n\n");
            if (s.aborted) {
                sb.append("- **Aborted:** ").append(s.abortReason).append('\n');
            }
            for (Scenario.Check c : s.checks) {
                String tag = c.notTested() ? "NOT TESTED" : c.pass() ? "PASS" : "FAIL";
                sb.append("- ").append(tag).append(" — ").append(c.name()).append(": ").append(oneLine(c.evidence()))
                        .append('\n');
            }
            for (String n : s.notes) {
                sb.append("- note: ").append(oneLine(n)).append('\n');
            }
            for (String e : s.errors) {
                sb.append("- **log error:** `").append(oneLine(e).replace('`', '\'')).append("`\n");
            }
        }
        if (!h.unrelatedErrors.isEmpty()) {
            sb.append("\n## Other errors in the log (not from the mod)\n\n");
            for (int i = 0; i < Math.min(40, h.unrelatedErrors.size()); i++) {
                sb.append("- `").append(oneLine(h.unrelatedErrors.get(i)).replace('`', '\'')).append("`\n");
            }
        }
        return sb.toString();
    }

    static JsonObject json(Harness h, List<Scenario> all, String outcome) {
        JsonObject root = new JsonObject();
        root.addProperty("generated", Harness.now());
        root.addProperty("outcome", outcome);
        root.addProperty("world", Harness.WORLD);
        root.addProperty("seed", Harness.SEED);
        JsonArray scenarios = new JsonArray();
        for (Scenario s : all) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id);
            o.addProperty("title", s.title);
            o.addProperty("status", s.status().name());
            o.addProperty("ticks", s.ticks);
            o.addProperty("seconds", s.endNanos > s.startNanos ? (s.endNanos - s.startNanos) / 1e9 : 0);
            if (s.aborted) {
                o.addProperty("aborted", s.abortReason);
            }
            JsonArray checks = new JsonArray();
            for (Scenario.Check c : s.checks) {
                JsonObject co = new JsonObject();
                co.addProperty("name", c.name());
                co.addProperty("result", c.notTested() ? "NOT_TESTED" : c.pass() ? "PASS" : "FAIL");
                co.addProperty("evidence", c.evidence());
                checks.add(co);
            }
            o.add("checks", checks);
            JsonArray notes = new JsonArray();
            s.notes.forEach(notes::add);
            o.add("notes", notes);
            JsonArray errors = new JsonArray();
            s.errors.forEach(errors::add);
            o.add("errors", errors);
            scenarios.add(o);
        }
        root.add("scenarios", scenarios);
        JsonArray perf = new JsonArray();
        for (Perf.Window w : h.perfWindows) {
            JsonObject o = new JsonObject();
            o.addProperty("label", w.label);
            o.addProperty("seconds", w.seconds);
            o.addProperty("ticks", w.ticks);
            o.addProperty("tickMeanMs", w.tickMeanMs);
            o.addProperty("tickP95Ms", w.tickP95Ms);
            o.addProperty("tickMaxMs", w.tickMaxMs);
            o.addProperty("frames", w.frames);
            o.addProperty("fps", w.fps);
            o.addProperty("frameMeanMs", w.frameMeanMs);
            o.addProperty("frameP95Ms", w.frameP95Ms);
            o.addProperty("frameP99Ms", w.frameP99Ms);
            JsonObject mods = new JsonObject();
            for (Map.Entry<String, Double> e : w.moduleUsPerTick.entrySet()) {
                mods.addProperty(e.getKey(), e.getValue());
            }
            o.add("moduleMicrosPerTick", mods);
            JsonObject alloc = new JsonObject();
            for (Map.Entry<String, Double> e : w.allocBytesPerSec.entrySet()) {
                alloc.addProperty(e.getKey(), e.getValue());
            }
            o.add("allocBytesPerSecByThread", alloc);
            perf.add(o);
        }
        root.add("performance", perf);
        JsonArray other = new JsonArray();
        h.unrelatedErrors.forEach(other::add);
        root.add("otherErrors", other);
        return root;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
