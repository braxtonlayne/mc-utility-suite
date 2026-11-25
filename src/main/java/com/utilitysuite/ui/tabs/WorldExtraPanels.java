package com.utilitysuite.ui.tabs;

import com.utilitysuite.ui.ModulePanels;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.world.changes.ChangeTrackerModule;
import com.utilitysuite.world.replay.ClipShelf;
import com.utilitysuite.world.replay.GhostLibrary;
import com.utilitysuite.world.replay.ReplayTheatreModule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Custom rows in two World module cards: Replay Theatre (saved parkour ghosts with Load / Delete, saved kill-cam clips
 * with Open / Delete, Save clip now) and Change Tracker (clear the snapshot of this chunk, this dimension or the whole
 * world; the wider two ask for a second click first). Registered from {@link WorldTab#registerPanels()}.
 */
final class WorldExtraPanels {

    private static final int ROW_H = 30;
    private static final int MAX_ROWS = 8;
    private static final String K_ARM = "world.change_clear_armed";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm")
            .withZone(ZoneId.systemDefault());

    private WorldExtraPanels() {
    }

    static void register() {
        ModulePanels.register(ReplayTheatreModule.ID, (p, m) -> {
            if (m instanceof ReplayTheatreModule r) {
                replayRows(p, r);
            }
        });
        ModulePanels.register(ChangeTrackerModule.ID, (p, m) -> {
            if (m instanceof ChangeTrackerModule c) {
                changeRows(p, c);
            }
        });
    }

    // ---- Replay Theatre -----------------------------------------------------------------------------------------

    private static void replayRows(Panel p, ReplayTheatreModule r) {
        QuickActions.actionRow(p, "Kill-cam clip", "Saves the last 30 seconds to disk; reopen it below.", "Save clip",
                PillButton.Style.SECONDARY, () -> {
                    p.toast(r.saveKillCam());
                    p.rebuild();
                });
        if (!r.filesOpen()) {
            p.paragraph("Saved ghosts and clips of this world appear here once the module is on in a world.",
                    Panel.GRAY);
            return;
        }
        List<GhostLibrary.Course> ghosts = r.savedGhosts();
        p.subheading("Saved ghosts (" + ghosts.size() + ")");
        if (ghosts.isEmpty()) {
            p.paragraph("None yet: finish a run between the parkour markers, or press G in the replay screen.",
                    Panel.GRAY);
        }
        for (int i = 0; i < Math.min(MAX_ROWS, ghosts.size()); i++) {
            GhostLibrary.Course c = ghosts.get(i);
            String id = c.id();
            String sub = "best " + seconds(c.bestTicks()) + "  ·  " + WorldTab.shortDim(c.dimension()) + "  ·  "
                    + WHEN.format(Instant.ofEpochMilli(c.savedMs()));
            twoButtonRow(p, c.name(), sub, c.name() + "\nStart " + xyz(c.sx(), c.sy(), c.sz()) + "\nFinish "
                            + xyz(c.fx(), c.fy(), c.fz()), "Load", "Use this course's markers, ghost and best",
                    () -> p.toast(r.loadGhost(id)), () -> {
                        p.toast(r.deleteGhost(id));
                        p.rebuild();
                    });
        }
        List<ClipShelf.Entry> clips = r.savedClips();
        p.subheading("Saved clips (" + clips.size() + ")");
        if (clips.isEmpty()) {
            p.paragraph("None yet: Save clip above, or K in the replay screen.", Panel.GRAY);
        }
        for (int i = 0; i < Math.min(MAX_ROWS, clips.size()); i++) {
            ClipShelf.Entry e = clips.get(i);
            String id = e.id();
            String title = "Kill-cam " + WHEN.format(Instant.ofEpochMilli(e.info().savedMs()));
            String sub = Math.round(e.info().durationSeconds()) + " s  ·  " + e.info().events() + " events  ·  "
                    + Math.max(1, e.bytes() / 1024) + " KB";
            twoButtonRow(p, title, sub, title, "Open", "Open in the replay screen", () -> p.toast(r.openClip(id)),
                    () -> {
                        p.toast(r.deleteClip(id));
                        p.rebuild();
                    });
        }
    }

    private static void twoButtonRow(Panel p, String title, String sub, String tooltip, String action,
                                     String actionTip, Runnable onAction, Runnable onDelete) {
        int w = p.width();
        int delW = 50;
        int actW = 44;
        int gap = 6;
        int textW = w - delW - actW - gap - 12;
        var row = p.listRow(null);
        row.h = ROW_H - 2;
        row.tooltip(tooltip);
        var name = p.rowLabel(title, 4, textW, Panel.WHITE);
        name.y -= 3;
        var info = p.rowLabel(sub, 4, textW, Panel.GRAY);
        info.y += 9;
        int shift = (ROW_H - Panel.ROW) / 2;
        p.button(action, actionTip, w - delW - gap - actW, actW, PillButton.Style.SECONDARY, b -> onAction.run())
                .y += shift;
        p.button("Delete", null, w - delW, delW, PillButton.Style.DANGER_SOFT, b -> onDelete.run()).y += shift;
        p.nextRow();
        p.gap(ROW_H - Panel.ROW);
    }

    private static String seconds(int ticks) {
        if (ticks < 0) {
            return "-";
        }
        long cs = Math.round(ticks * 5.0);
        return (cs / 100) + "." + (cs % 100 < 10 ? "0" : "") + (cs % 100) + "s";
    }

    private static String xyz(double x, double y, double z) {
        return Math.round(x) + " " + Math.round(y) + " " + Math.round(z);
    }

    // ---- Change Tracker -----------------------------------------------------------------------------------------

    private static void changeRows(Panel p, ChangeTrackerModule c) {
        p.paragraph(c.filesOpen() ? c.storedChunks() + " chunk snapshots stored in this dimension"
                : "Snapshots load once the module is on in a world.", Panel.GRAY);
        QuickActions.actionRow(p, "This chunk", "Forget the snapshot of the chunk you stand in.", "Clear chunk",
                PillButton.Style.SECONDARY, () -> {
                    p.setState(K_ARM, "");
                    p.toast(c.clearChunkUnderPlayer());
                    p.rebuild();
                });
        armedRow(p, "This dimension", "Forget every snapshot in this dimension (a second click confirms).",
                "dimension", "Clear dimension", c::clearDimension);
        armedRow(p, "Whole world", "Forget every snapshot of this world in all dimensions (a second click confirms).",
                "all", "Clear all", c::clearAll);
    }

    /** First click arms (the pill turns into "Confirm"), the second runs it and toasts the result. */
    private static void armedRow(Panel p, String label, String tooltip, String key, String button,
                                 java.util.function.Supplier<String> action) {
        boolean armed = key.equals(p.state(K_ARM, ""));
        QuickActions.actionRow(p, label, tooltip, armed ? "Confirm" : button,
                armed ? PillButton.Style.DANGER : PillButton.Style.DANGER_SOFT, () -> {
                    if (key.equals(p.state(K_ARM, ""))) {
                        p.setState(K_ARM, "");
                        p.toast(action.get());
                    } else {
                        p.setState(K_ARM, key);
                        p.toast("Click Confirm to clear " + (key.equals("all") ? "every snapshot of this world"
                                : "this dimension's snapshots"));
                    }
                    p.rebuild();
                });
    }
}
