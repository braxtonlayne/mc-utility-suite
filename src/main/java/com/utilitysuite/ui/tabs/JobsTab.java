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
import com.utilitysuite.core.api.PlanPreview;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.jobs.schematic.SchematicBootstrap;
import com.utilitysuite.jobs.schematic.SchematicJobCard;
import com.utilitysuite.jobs.schematic.SchematicToolkit;
import com.utilitysuite.ui.GoalConsole;
import com.utilitysuite.ui.HudStatusLine;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.SettingsRenderer;
import com.utilitysuite.ui.UiPreview;
import com.utilitysuite.ui.kit.CardNode;
import com.utilitysuite.ui.kit.GoalHeroCard;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.UiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jobs tab: the active job as a {@link GoalHeroCard} (Pause / Stop / Plan), the dry-run preview of the goal typed in
 * the top-bar Goal Console (with Start), parse errors, the queue, the schematic importer with its job card, then every
 * JOBS module (Survival Autopilot first) as a settings card.
 */
public final class JobsTab {

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
            Optional<JobSnapshot> a = UiPreview.activeJob(g);
            sb.append(a.map(j -> j.jobId() + ":" + j.status()).orElse("-"));
            sb.append('/').append(UiPreview.queuedJobs(g).size());
            sb.append('/').append(UiPreview.lastPreview(g).map(System::identityHashCode).orElse(0));
        }
        SchematicToolkit t = SchematicBootstrap.toolkit();
        if (t != null) {
            sb.append('/').append(t.jobCard().map(SchematicJobCard::phase).orElse("-"));
        }
        sb.append('/').append(schematicFiles.size()).append(listing);
        com.utilitysuite.core.api.IStorageIndex st = Services.storageOrNull();
        sb.append('/').append(st == null ? 0 : st.crafterTemplateNames().hashCode());
        return sb.toString();
    }

    public static void build(Panel p) {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            p.card("Goal Console", "not available");
            p.text("The goal engine is not loaded.", Panel.RED);
        } else {
            activeJob(p, goals);
            preview(p, goals);
            queue(p, goals);
            crafterTemplates(p, goals);
        }
        schematics(p);
        GeneratorCards.build(p);
        List<IModule> mods = new ArrayList<>(Services.modules().byCategory(ModuleCategory.JOBS));
        if (!mods.isEmpty()) {
            p.section("Job modules");
        }
        mods.sort((a, b) -> Boolean.compare(!"survival_autopilot".equals(a.id()), !"survival_autopilot".equals(b.id())));
        for (IModule m : mods) {
            SettingsRenderer.module(p, m);
        }
    }

    // ---- active job -------------------------------------------------------------------------------------------------

    private static void activeJob(Panel p, IGoalEngine goals) {
        GoalHeroCard hero = new GoalHeroCard(p.font(), () -> UiPreview.activeJob(goals), p.canvasWidth());
        int top = p.block(hero);
        if (hero.isIdle()) {
            return;
        }
        Optional<JobSnapshot> active = UiPreview.activeJob(goals);
        if (active.isEmpty()) {
            return;
        }
        long id = active.get().jobId();
        boolean paused = active.get().status() == JobStatus.PAUSED;
        boolean show = p.flag(K_SHOW_PLAN);
        int x = UiTheme.CARD_PAD;
        int py = top + GoalHeroCard.PILLS_Y;
        p.placeAbs(new PillButton(p.font(), paused ? "Resume" : "Pause", 60,
                paused ? PillButton.Style.PRIMARY : PillButton.Style.SECONDARY, b -> {
            if (paused) {
                goals.resume(id);
            } else {
                goals.pause(id);
            }
            p.rebuild();
        }).pill(true), x, py);
        p.placeAbs(new PillButton(p.font(), "Stop", 52, PillButton.Style.DANGER_SOFT, b -> {
            goals.cancel(id);
            p.rebuild();
        }).pill(true).tooltip("Cancel this job"), x + 66, py);
        p.placeAbs(new PillButton(p.font(), show ? "Hide plan" : "Plan", 64, PillButton.Style.SECONDARY, b -> {
            p.setFlag(K_SHOW_PLAN, !show);
            p.rebuild();
        }).pill(true).selected(show), x + 124, py);
        if (show) {
            List<String> steps = goals.planSteps(id);
            p.card("Plan", steps.size() + (steps.size() == 1 ? " step" : " steps"));
            int n = Math.min(40, steps.size());
            for (int i = 0; i < n; i++) {
                p.text((i + 1) + ".  " + steps.get(i), Panel.WHITE);
            }
            if (steps.size() > n) {
                p.text("+ " + (steps.size() - n) + " more", Panel.GRAY);
            }
            if (steps.isEmpty()) {
                p.paragraph("No plan steps.", Panel.GRAY);
            }
        }
    }

    // ---- dry-run preview / errors ------------------------------------------------------------------------------------

    private static void preview(Panel p, IGoalEngine goals) {
        String err = p.state(GoalConsole.K_ERROR, "");
        if (!err.isEmpty()) {
            p.card("Could not read that goal", "", 0, CardNode.Tone.DANGER);
            p.paragraph(err, UiTheme.DANGER_TEXT);
            p.paragraph("Examples: gather 32 diamonds  ·  craft 16 hoppers  ·  travel to Base", Panel.GRAY);
        }
        Optional<PlanPreview> preview = UiPreview.lastPreview(goals);
        if (preview.isEmpty()) {
            return;
        }
        PlanPreview pv = preview.get();
        p.card("Dry-run preview", pv.interpretedTarget(), 70, CardNode.Tone.NORMAL);
        p.headerNode(new PillButton(p.font(), "Start job", 64, PillButton.Style.PRIMARY,
                b -> startPreviewed(p, goals, pv)).pill(true).tooltip("Queue this goal"), 0);
        int n = Math.min(MAX_STEPS, pv.steps().size());
        for (int i = 0; i < n; i++) {
            p.text((i + 1) + ".  " + pv.steps().get(i), Panel.WHITE);
        }
        if (pv.steps().size() > n) {
            p.text("+ " + (pv.steps().size() - n) + " more steps", Panel.GRAY);
        }
        if (pv.steps().isEmpty()) {
            p.paragraph("No steps needed.", Panel.GRAY);
        }
        if (!pv.missingMaterials().isEmpty() || !pv.warnings().isEmpty()) {
            p.gap(4);
        }
        if (!pv.missingMaterials().isEmpty()) {
            p.paragraph("Missing: " + materials(pv.missingMaterials(), 8), Panel.YELLOW);
        }
        for (String warn : pv.warnings()) {
            p.paragraph("! " + warn, Panel.YELLOW);
        }
    }

    private static void startPreviewed(Panel p, IGoalEngine goals, PlanPreview pv) {
        long id = goals.submit(pv.request());
        p.setState(GoalConsole.K_GOAL, "");
        p.setState(GoalConsole.K_PREVIEWED, "");
        p.toast("Queued job #" + id + ": " + pv.request().label());
        p.rebuild();
    }

    // ---- queue ------------------------------------------------------------------------------------------------------

    private static void queue(Panel p, IGoalEngine goals) {
        List<JobSnapshot> queued = UiPreview.queuedJobs(goals);
        if (queued.isEmpty()) {
            return;
        }
        p.card("Queue", queued.size() + " waiting");
        int w = p.width();
        for (JobSnapshot j : queued) {
            long id = j.jobId();
            p.listRow(null);
            p.rowLabel("#" + id, 4, 30, Panel.GRAY);
            p.rowLabel(HudStatusLine.prettyLabel(j.goalLabel() == null ? "" : j.goalLabel()), 34, w - 34 - 150,
                    Panel.WHITE);
            p.rowLabel(GoalHeroCard.chipText(j.status()), w - 140, 76, GoalHeroCard.statusColor(j.status()));
            p.button("Cancel", null, w - 58, 56, PillButton.Style.DANGER_SOFT, b -> {
                goals.cancel(id);
                p.rebuild();
            });
            p.nextRow();
        }
    }

    // ---- crafter templates ------------------------------------------------------------------------------------------

    /** Saved crafter templates ("crafter save <name>"), each with Apply (queues "crafter apply <name>"). */
    private static void crafterTemplates(Panel p, IGoalEngine goals) {
        com.utilitysuite.core.api.IStorageIndex st = Services.storageOrNull();
        List<String> names = st == null ? List.of() : st.crafterTemplateNames();
        if (names.isEmpty()) {
            return;
        }
        p.card("Crafter templates", names.size() + " saved");
        int w = p.width();
        for (String name : names) {
            p.listRow(null);
            p.rowLabel(name, 4, w - 4 - 70, Panel.WHITE);
            p.button("Apply", null, w - 64, 60, PillButton.Style.PRIMARY, b -> {
                long id = goals.submit(new GoalRequest.Crafter(true, name, CostProfile.FASTEST,
                        DataSourceMode.OBSERVED_ONLY));
                p.toast("Queued job #" + id + ": apply crafter template " + name);
                p.rebuild();
            });
            p.nextRow();
        }
    }

    // ---- schematics -------------------------------------------------------------------------------------------------

    private static void schematics(Panel p) {
        SchematicToolkit toolkit = SchematicBootstrap.toolkit();
        IGoalEngine goals = Services.goalsOrNull();
        if (toolkit == null || goals == null) {
            p.card("Schematic importer", "not loaded");
            return;
        }
        p.card("Schematic importer", toolkit.library().roots().size() + " folders", 84, CardNode.Tone.NORMAL);
        p.headerNode(new PillButton(p.font(), listing ? "Listing..." : "Refresh list", 80,
                PillButton.Style.SECONDARY, b -> refreshList(toolkit, p)).tooltip("Scans the schematics folders"), 0);
        int w = p.width();
        if (!schematicListError.isEmpty()) {
            p.paragraph(schematicListError, Panel.RED);
        }
        List<Path> files = schematicFiles;
        if (files.isEmpty() && !listing) {
            p.paragraph("No schematic files listed yet. Press Refresh list to scan.", Panel.GRAY);
        }
        String selected = p.state(K_SCHEMATIC, "");
        int shown = Math.min(MAX_FILES, files.size());
        for (int i = 0; i < shown; i++) {
            Path f = files.get(i);
            String abs = f.toAbsolutePath().toString();
            boolean sel = abs.equals(selected);
            p.listRow(r -> {
                p.setState(K_SCHEMATIC, sel ? "" : abs);
                p.rebuild();
            }).label(p.font(), f.getFileName().toString(), w - 12, Panel.WHITE).selected(sel);
            p.nextRow();
        }
        if (files.size() > shown) {
            p.text("+ " + (files.size() - shown) + " more files", Panel.GRAY);
        }
        if (!selected.isEmpty()) {
            p.divider();
            boolean here = !"coords".equals(p.state(K_ANCHOR_MODE, "here"));
            p.rowLabel("Anchor", 0, 60, Panel.WHITE);
            int cw = Math.min(170, w / 2);
            p.cycle(List.of("here", "coords"), here ? "here" : "coords", v -> "here".equals(v) ? "Here" : "Coordinates",
                    w - cw, cw, v -> {
                        p.setState(K_ANCHOR_MODE, v);
                        p.rebuild();
                    });
            p.nextRow();
            if (!here) {
                p.rowLabel("Origin", 0, 60, Panel.WHITE);
                p.textField(K_ANCHOR, "x y z", w - cw, cw);
                p.nextRow();
            }
            p.toggle("Gather materials only", p.flag(K_GATHER_ONLY), 0, w, v -> p.setFlag(K_GATHER_ONLY, v));
            p.nextRow();
            p.button("Submit build", null, w - 110, 110, PillButton.Style.PRIMARY, b -> submitBuild(p, goals, selected));
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
        p.card("Schematic job", c.name() + " · " + c.format() + " · " + c.sizeX() + "x" + c.sizeY() + "x"
                + c.sizeZ());
        p.dynamic(() -> toolkit.jobCard().map(SchematicJobCard::statusLine).orElse("Finished"), Panel.WHITE);
        p.text("Anchor " + c.anchorX() + " " + c.anchorY() + " " + c.anchorZ()
                + (c.gatherOnly() ? "  (gather only)" : ""), Panel.GRAY);
        if (!c.missing().isEmpty()) {
            p.paragraph("Missing: " + materials(c.missing(), 6), Panel.YELLOW);
        }
        if (!c.unobtainable().isEmpty()) {
            p.paragraph("Not obtainable in survival: " + materials(c.unobtainable(), 4), Panel.RED);
        }
        for (String s : c.survey()) {
            p.text(s, Panel.GRAY);
        }
        for (String n : c.notes()) {
            p.text(n, Panel.GRAY);
        }
        boolean paused = "PAUSED".equals(c.phase());
        p.gap(2);
        p.button(paused ? "Resume build" : "Pause build", null, 0, 96, PillButton.Style.SECONDARY, b -> {
            toolkit.setPaused(!paused);
            p.rebuild();
        });
        p.button("Cancel build", null, 102, 96, PillButton.Style.DANGER_SOFT, b -> {
            toolkit.cancel();
            p.rebuild();
        });
        int scaffold = toolkit.scaffoldCount();
        if (scaffold > 0) {
            p.button("Remove scaffolding (" + scaffold + ")", null, 204, 150, PillButton.Style.SECONDARY, b -> {
                toolkit.removeScaffolding();
                p.rebuild();
            });
        }
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
