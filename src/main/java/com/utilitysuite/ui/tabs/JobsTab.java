package com.utilitysuite.ui.tabs;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.PlanPreview;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.jobs.schematic.SchematicBootstrap;
import com.utilitysuite.jobs.schematic.SchematicJobCard;
import com.utilitysuite.jobs.schematic.SchematicToolkit;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.SettingsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jobs tab: Goal Console with Dry-Run Preview and Start, the active Goal Card (Goal, Progress, Now, Why, Next; Pause,
 * Stop, Show Plan), the queue, the schematic importer with its job card, then every JOBS module (Survival Autopilot
 * first) rendered generically.
 */
public final class JobsTab {

    private static final String K_GOAL = "jobs.goal";
    private static final String K_ERROR = "jobs.error";
    private static final String K_SHOW_PLAN = "jobs.showPlan";
    private static final String K_SCHEMATIC = "jobs.schematic";
    private static final String K_ANCHOR_MODE = "jobs.anchorMode";
    private static final String K_ANCHOR = "jobs.anchor";
    private static final String K_GATHER_ONLY = "jobs.gatherOnly";
    private static final int MAX_STEPS = 14;
    private static final int MAX_FILES = 10;

    private static volatile List<Path> schematicFiles = List.of();
    private static volatile String schematicListError = "";
    private static volatile boolean listing;

    private JobsTab() {
    }

    public static String signature() {
        StringBuilder sb = new StringBuilder();
        IGoalEngine g = Services.goalsOrNull();
        if (g != null) {
            Optional<JobSnapshot> a = g.activeJob();
            sb.append(a.map(j -> j.jobId() + ":" + j.status()).orElse("-"));
            sb.append('/').append(g.queuedJobs().size());
            sb.append('/').append(g.lastPreview().map(p -> System.identityHashCode(p)).orElse(0));
        }
        SchematicToolkit t = SchematicBootstrap.toolkit();
        if (t != null) {
            sb.append('/').append(t.jobCard().map(SchematicJobCard::phase).orElse("-"));
        }
        sb.append('/').append(schematicFiles.size()).append(listing);
        return sb.toString();
    }

    public static void build(Panel p) {
        goalConsole(p);
        goalCard(p);
        schematics(p);
        p.heading("Job modules");
        List<IModule> mods = new ArrayList<>(Services.modules().byCategory(ModuleCategory.JOBS));
        mods.sort((a, b) -> Boolean.compare(!"survival_autopilot".equals(a.id()), !"survival_autopilot".equals(b.id())));
        for (IModule m : mods) {
            SettingsRenderer.module(p, m);
        }
    }

    // ---- goal console -----------------------------------------------------------------------------------------------

    private static void goalConsole(Panel p) {
        p.heading("Goal Console");
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            p.text("The goal engine is not loaded.", Panel.RED);
            return;
        }
        int w = p.width();
        p.textField(K_GOAL, "e.g. gather 32 diamonds   craft 16 hoppers   travel to Base", 0, w);
        p.nextRow();
        int bw = (w - 4) / 2;
        p.button("Dry-Run Preview", "Plan without doing anything", 0, bw, b -> {
            ParseResult r = goals.parse(p.state(K_GOAL, ""));
            if (!r.isOk()) {
                p.setState(K_ERROR, r.error() + (r.suggestions().isEmpty() ? "" : "  Try: "
                        + String.join(", ", r.suggestions())));
            } else {
                p.setState(K_ERROR, "");
                goals.requestPreview(r.request());
                p.toast("Planning: " + r.request().label());
            }
            p.rebuild();
        });
        p.button("Start", "Queue this goal", bw + 4, bw, b -> {
            ParseResult r = goals.parse(p.state(K_GOAL, ""));
            if (!r.isOk()) {
                p.setState(K_ERROR, r.error());
            } else {
                p.setState(K_ERROR, "");
                long id = goals.submit(r.request());
                p.setState(K_GOAL, "");
                p.toast("Queued job #" + id + ": " + r.request().label());
            }
            p.rebuild();
        });
        p.nextRow();
        String err = p.state(K_ERROR, "");
        if (!err.isEmpty()) {
            p.text(err, Panel.RED);
        }
        Optional<PlanPreview> preview = goals.lastPreview();
        if (preview.isPresent()) {
            PlanPreview pv = preview.get();
            p.text("Preview: " + pv.interpretedTarget(), Panel.GREEN);
            int n = Math.min(MAX_STEPS, pv.steps().size());
            for (int i = 0; i < n; i++) {
                p.textIndented((i + 1) + ". " + pv.steps().get(i), Panel.WHITE, 8);
            }
            if (pv.steps().size() > n) {
                p.textIndented("+ " + (pv.steps().size() - n) + " more steps", Panel.GRAY, 8);
            }
            if (pv.steps().isEmpty()) {
                p.textIndented("(no steps)", Panel.GRAY, 8);
            }
            if (!pv.missingMaterials().isEmpty()) {
                p.textIndented("Missing: " + materials(pv.missingMaterials(), 8), Panel.YELLOW, 8);
            }
            for (String warn : pv.warnings()) {
                p.textIndented("! " + warn, Panel.YELLOW, 8);
            }
        }
    }

    // ---- goal card --------------------------------------------------------------------------------------------------

    private static void goalCard(Panel p) {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            return;
        }
        p.heading("Active job");
        Optional<JobSnapshot> active = goals.activeJob();
        if (active.isEmpty()) {
            p.text("No active job.", Panel.GRAY);
        } else {
            long id = active.get().jobId();
            p.dynamic(() -> field(goals, j -> "Goal: " + j.goalLabel() + "  [" + j.status() + "]"), Panel.WHITE);
            p.dynamic(() -> field(goals, j -> j.progressTarget() > 0
                    ? "Progress: " + j.progressCurrent() + "/" + j.progressTarget() : "Progress: -"), Panel.WHITE);
            p.dynamic(() -> field(goals, j -> "Now: " + j.currentStep()), Panel.WHITE);
            p.dynamic(() -> field(goals, j -> "Why: " + j.reason()), Panel.GRAY);
            p.dynamic(() -> field(goals, j -> "Next: " + (j.nextStep() == null || j.nextStep().isEmpty() ? "-"
                    : j.nextStep())), Panel.GRAY);
            int bw = (p.width() - 8) / 3;
            boolean paused = active.get().status() == JobStatus.PAUSED;
            p.button(paused ? "Resume" : "Pause", 0, bw, b -> {
                if (paused) {
                    goals.resume(id);
                } else {
                    goals.pause(id);
                }
                p.rebuild();
            });
            p.button("Stop", "Cancel this job", bw + 4, bw, b -> {
                goals.cancel(id);
                p.rebuild();
            });
            boolean show = p.flag(K_SHOW_PLAN);
            p.button(show ? "Hide Plan" : "Show Plan", 2 * (bw + 4), bw, b -> {
                p.setFlag(K_SHOW_PLAN, !show);
                p.rebuild();
            });
            p.nextRow();
            if (show) {
                List<String> steps = goals.planSteps(id);
                int n = Math.min(40, steps.size());
                for (int i = 0; i < n; i++) {
                    p.textIndented(steps.get(i), Panel.WHITE, 8);
                }
                if (steps.size() > n) {
                    p.textIndented("+ " + (steps.size() - n) + " more", Panel.GRAY, 8);
                }
            }
        }
        List<JobSnapshot> queued = goals.queuedJobs();
        if (!queued.isEmpty()) {
            p.text("Queued:", Panel.GRAY);
            for (JobSnapshot j : queued) {
                long id = j.jobId();
                p.rowLabel("#" + id + " " + j.goalLabel() + " (" + j.status() + ")", 4, p.width() - 74,
                        Panel.WHITE);
                p.button("Cancel", p.width() - 66, 66, b -> {
                    goals.cancel(id);
                    p.rebuild();
                });
                p.nextRow();
            }
        }
    }

    private interface JobField {
        String get(JobSnapshot j);
    }

    private static String field(IGoalEngine goals, JobField f) {
        Optional<JobSnapshot> a = goals.activeJob();
        return a.isEmpty() ? "" : f.get(a.get());
    }

    // ---- schematics -------------------------------------------------------------------------------------------------

    private static void schematics(Panel p) {
        p.heading("Schematic importer");
        SchematicToolkit toolkit = SchematicBootstrap.toolkit();
        IGoalEngine goals = Services.goalsOrNull();
        if (toolkit == null || goals == null) {
            p.text("The schematic toolkit is not loaded.", Panel.GRAY);
            return;
        }
        int w = p.width();
        p.button(listing ? "Listing..." : "Refresh file list", "Scans the schematics folders", 0, 140, b -> {
            refreshList(toolkit, p);
        });
        p.nextRow();
        if (!schematicListError.isEmpty()) {
            p.text(schematicListError, Panel.RED);
        }
        List<Path> files = schematicFiles;
        if (files.isEmpty() && !listing) {
            p.text("No schematic files listed yet (folders: " + toolkit.library().roots().size() + ").",
                    Panel.GRAY);
        }
        String selected = p.state(K_SCHEMATIC, "");
        int shown = Math.min(MAX_FILES, files.size());
        for (int i = 0; i < shown; i++) {
            Path f = files.get(i);
            String abs = f.toAbsolutePath().toString();
            boolean sel = abs.equals(selected);
            p.button((sel ? "▶ " : "") + f.getFileName(), 0, w, b -> {
                p.setState(K_SCHEMATIC, abs);
                p.rebuild();
            });
            p.nextRow();
        }
        if (files.size() > shown) {
            p.text("+ " + (files.size() - shown) + " more files", Panel.GRAY);
        }
        if (!selected.isEmpty()) {
            boolean here = !"coords".equals(p.state(K_ANCHOR_MODE, "here"));
            p.button(here ? "Anchor: here" : "Anchor: coordinates", 0, 140, b -> {
                p.setState(K_ANCHOR_MODE, here ? "coords" : "here");
                p.rebuild();
            });
            if (!here) {
                p.textField(K_ANCHOR, "x y z", 144, w - 144);
            }
            p.nextRow();
            boolean gatherOnly = p.flag(K_GATHER_ONLY);
            p.toggle("Gather materials only", gatherOnly, 0, 200, v -> p.setFlag(K_GATHER_ONLY, v));
            p.button("Submit build", 204, w - 204, b -> submitBuild(p, goals, selected));
            p.nextRow();
        }
        Optional<SchematicJobCard> card = toolkit.jobCard();
        if (card.isPresent()) {
            schematicCard(p, toolkit, card.get());
        }
    }

    private static void refreshList(SchematicToolkit toolkit, Panel p) {
        if (listing) {
            return;
        }
        listing = true;
        schematicListError = "";
        p.rebuild();
        Minecraft mc = Minecraft.getInstance();
        toolkit.listSchematics().whenComplete((list, err) -> mc.execute(() -> {
            listing = false;
            if (err != null) {
                schematicListError = "Could not list schematics: " + err.getMessage();
            } else {
                schematicFiles = List.copyOf(list);
            }
        }));
    }

    private static void submitBuild(Panel p, IGoalEngine goals, String path) {
        long anchor = Long.MIN_VALUE;
        if ("coords".equals(p.state(K_ANCHOR_MODE, "here"))) {
            String[] parts = p.state(K_ANCHOR, "").trim().split("[\\s,]+");
            try {
                anchor = BlockPos.asLong(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            } catch (RuntimeException e) {
                p.toast("Anchor needs three whole numbers: x y z");
                return;
            }
        } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                anchor = mc.player.blockPosition().asLong();
            }
        }
        IWorldKnowledgeService wk = Services.worldKnowledgeOrNull();
        DataSourceMode mode = wk == null ? DataSourceMode.OBSERVED_ONLY : wk.routingMode();
        long id = goals.submit(new GoalRequest.Build(path, anchor, p.flag(K_GATHER_ONLY), CostProfile.FASTEST, mode));
        p.toast("Queued build job #" + id);
        p.rebuild();
    }

    private static void schematicCard(Panel p, SchematicToolkit toolkit, SchematicJobCard c) {
        p.text("Schematic job: " + c.name() + " (" + c.format() + ", " + c.sizeX() + "x" + c.sizeY() + "x"
                + c.sizeZ() + ")", Panel.GREEN);
        p.dynamic(() -> toolkit.jobCard().map(SchematicJobCard::statusLine).orElse("Finished"), Panel.WHITE);
        p.textIndented("Anchor " + c.anchorX() + " " + c.anchorY() + " " + c.anchorZ()
                + (c.gatherOnly() ? "  (gather only)" : ""), Panel.GRAY, 8);
        if (!c.missing().isEmpty()) {
            p.textIndented("Missing: " + materials(c.missing(), 6), Panel.YELLOW, 8);
        }
        if (!c.unobtainable().isEmpty()) {
            p.textIndented("Not obtainable in survival: " + materials(c.unobtainable(), 4), Panel.RED, 8);
        }
        for (String s : c.survey()) {
            p.textIndented(s, Panel.GRAY, 8);
        }
        for (String n : c.notes()) {
            p.textIndented(n, Panel.GRAY, 8);
        }
        int bw = (p.width() - 4) / 2;
        boolean paused = "PAUSED".equals(c.phase());
        p.button(paused ? "Resume build" : "Pause build", 0, bw, b -> {
            toolkit.setPaused(!paused);
            p.rebuild();
        });
        p.button("Cancel build", bw + 4, bw, b -> {
            toolkit.cancel();
            p.rebuild();
        });
        p.nextRow();
    }

    static String materials(Map<String, Integer> items, int max) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (i++ >= max) {
                sb.append(", +").append(items.size() - max).append(" more");
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String id = e.getKey();
            sb.append(e.getValue()).append(" ").append(id.startsWith("minecraft:") ? id.substring(10) : id);
        }
        return sb.toString();
    }
}
