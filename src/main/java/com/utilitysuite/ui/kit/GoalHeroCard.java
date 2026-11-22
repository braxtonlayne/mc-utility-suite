package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.ui.HudStatusLine;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The active job at a glance (Jobs tab): target item icon, readable goal title with a status chip, X/Y count, a 4 px
 * progress bar and Now / Why / Next lines. The Pause / Stop / Plan pills are separate nodes placed at
 * {@link #PILLS_Y}. Text is rebuilt in {@link #refresh(Font)} from the snapshot supplier (every few ticks); rendering
 * only draws cached strings. Without a job it shows a compact idle state.
 */
public final class GoalHeroCard extends UiNode {

    public static final int ACTIVE_H = 136;
    public static final int IDLE_H = 56;
    public static final int PILLS_Y = 108;
    private static final int PAD = UiTheme.CARD_PAD;
    private static final int TEXT_X = 54;
    private static final int CAPTION_W = 34;

    private final Supplier<Optional<JobSnapshot>> source;
    private final boolean idle;
    private ItemStack icon = ItemStack.EMPTY;
    private String iconId;
    private String title = "";
    private String count = "";
    private String percent = "";
    private int percentW;
    private String chip = "";
    private int chipW;
    private int chipColor;
    private float progress;
    private boolean hasTarget;
    private String now = "";
    private String why = "";
    private String next = "";
    private JobStatus lastStatus;
    private int lastCur = Integer.MIN_VALUE;
    private int lastTgt = Integer.MIN_VALUE;
    private String lastStep;
    private String lastReason;
    private String lastNext;
    private String lastLabel;

    public GoalHeroCard(Font font, Supplier<Optional<JobSnapshot>> source, int w) {
        super(w, 0);
        this.source = source;
        Optional<JobSnapshot> j = safe();
        idle = j.isEmpty();
        h = idle ? IDLE_H : ACTIVE_H;
        if (idle) {
            icon = ItemIcons.stack(Items.COMPASS);
            title = "No active job";
            now = UiRenderUtils.firstThatFits(font, w - TEXT_X - PAD,
                    "Type a goal in the bar above: Enter previews it, Enter again starts it.",
                    "Type a goal above: Enter previews, Enter again starts.",
                    "Type a goal above, press Enter twice.",
                    "Type a goal above.");
        } else {
            refresh(font);
        }
    }

    public boolean isIdle() {
        return idle;
    }

    private Optional<JobSnapshot> safe() {
        try {
            Optional<JobSnapshot> o = source.get();
            return o == null ? Optional.empty() : o;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void refresh(Font font) {
        if (idle) {
            return;
        }
        Optional<JobSnapshot> o = safe();
        if (o.isEmpty()) {
            return;
        }
        JobSnapshot j = o.get();
        if (j.status() == lastStatus && j.progressCurrent() == lastCur && j.progressTarget() == lastTgt
                && eq(j.currentStep(), lastStep) && eq(j.reason(), lastReason) && eq(j.nextStep(), lastNext)
                && eq(j.goalLabel(), lastLabel)) {
            return;
        }
        lastStatus = j.status();
        lastCur = j.progressCurrent();
        lastTgt = j.progressTarget();
        lastStep = j.currentStep();
        lastReason = j.reason();
        lastNext = j.nextStep();
        lastLabel = j.goalLabel();

        String id = HudStatusLine.itemIdOf(j.goalLabel());
        if (!id.equals(iconId)) {
            iconId = id;
            icon = ItemIcons.forId(id, fallbackIcon(j.goalLabel()));
        }
        chip = chipText(j.status());
        chipW = UiRenderUtils.width(font, chip) + 22;
        chipColor = statusColor(j.status());
        int titleRoom = w - TEXT_X - PAD - chipW - 8;
        title = UiRenderUtils.ellipsize(font, HudStatusLine.prettyLabel(j.goalLabel() == null ? "" : j.goalLabel()),
                titleRoom);
        hasTarget = j.progressTarget() > 0;
        if (hasTarget) {
            int cur = Math.max(0, Math.min(j.progressCurrent(), j.progressTarget()));
            progress = cur / (float) j.progressTarget();
            count = Math.max(0, j.progressCurrent()) + " / " + j.progressTarget();
            percent = Math.round(progress * 100) + "%";
        } else {
            progress = 0f;
            count = "No count target";
            percent = "";
        }
        percentW = UiRenderUtils.width(font, percent);
        int lineW = w - PAD - (PAD + CAPTION_W);
        now = UiRenderUtils.ellipsize(font, orDash(j.currentStep()), lineW);
        why = UiRenderUtils.ellipsize(font, orDash(j.reason()), lineW);
        next = UiRenderUtils.ellipsize(font, orDash(j.nextStep()), lineW);
    }

    /** Icon for goals without a target item: compass (travel), scaffolding (build), crafting table, book. */
    public static net.minecraft.world.item.Item fallbackIcon(String label) {
        String l = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("travel")) {
            return Items.COMPASS;
        }
        if (l.startsWith("build") || l.startsWith("gather materials")) {
            return Items.SCAFFOLDING;
        }
        if (l.startsWith("craft")) {
            return Items.CRAFTING_TABLE;
        }
        return Items.WRITABLE_BOOK;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s.trim();
    }

    public static String chipText(JobStatus s) {
        return switch (s) {
            case QUEUED -> "Queued";
            case PLANNING -> "Planning";
            case RUNNING -> "Running";
            case PAUSED -> "Paused";
            case REPLANNING -> "Replanning";
            case COMPLETED -> "Done";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
        };
    }

    public static int statusColor(JobStatus s) {
        return switch (s) {
            case RUNNING, COMPLETED -> UiTheme.RUNNING;
            case PAUSED, REPLANNING -> UiTheme.WARNING;
            case FAILED, CANCELLED -> UiTheme.DANGER;
            default -> UiTheme.ACCENT;
        };
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        float hv = hoverAmount(hover);
        HoloFrames.frame(g, sx, sy, w, h, idle ? FrameLevel.FRAMED : FrameLevel.HERO, !idle, 1f);
        if (hv > 0.01f) {
            g.fill(sx + 1, sy + 1, sx + w - 1, sy + h - 1, HoloTheme.fade(HoloTheme.argb(HoloToken.ACCENT), 0.06f * hv));
        }
        // icon tile
        int tile = 32;
        int tx = sx + PAD;
        int ty = sy + (idle ? (h - tile) / 2 : PAD);
        g.fill(tx, ty, tx + tile, ty + tile, HoloTheme.argb(HoloToken.FIELD));
        HoloFrames.edge(g, tx, ty, tile, tile, HoloTheme.argb(idle ? HoloToken.LINE_FAINT : HoloToken.LINE));
        UiRenderUtils.item(g, icon, tx + 4, ty + 4, 24);
        int x = sx + TEXT_X;
        if (idle) {
            UiRenderUtils.text(g, font, title, x, ty + 5, UiTheme.TEXT);
            UiRenderUtils.text(g, font, now, x, ty + 19, UiTheme.TEXT_MUTED);
            return;
        }
        UiRenderUtils.text(g, font, title, x, ty + 4, UiTheme.TEXT);
        // status chip
        int cx = sx + w - PAD - chipW;
        g.fill(cx, ty + 1, cx + chipW, ty + 15, HoloTheme.fade(chipColor, 0.08f));
        HoloFrames.edge(g, cx, ty + 1, chipW, 14, HoloTheme.fade(chipColor, 0.45f));
        g.fill(cx + 5, ty + 6, cx + 9, ty + 10, chipColor);
        UiRenderUtils.text(g, font, chip, cx + 15, ty + 4, chipColor);
        // count + percent
        UiRenderUtils.text(g, font, count, x, ty + 19, hasTarget ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED);
        if (!percent.isEmpty()) {
            UiRenderUtils.text(g, font, percent, sx + w - PAD - percentW, ty + 19, UiTheme.TEXT_DIM);
        }
        // progress bar
        int by = sy + 52;
        int bw = w - 2 * PAD;
        HoloFrames.progress(g, sx + PAD, by + 1, bw, HoloTheme.BAR, progress,
                lastStatus == JobStatus.PAUSED ? HoloToken.WARN : HoloToken.ACCENT, 1f);
        // now / why / next
        int ly = sy + 66;
        line(g, font, sx, ly, "Now", now, UiTheme.TEXT_SECONDARY);
        line(g, font, sx, ly + 12, "Why", why, UiTheme.TEXT_DIM);
        line(g, font, sx, ly + 24, "Next", next, UiTheme.TEXT_DIM);
    }

    private void line(GuiGraphics g, Font font, int sx, int y, String caption, String text, int color) {
        UiRenderUtils.text(g, font, caption, sx + PAD, y, UiTheme.TEXT_MUTED);
        UiRenderUtils.text(g, font, text, sx + PAD + CAPTION_W, y, color);
    }
}
