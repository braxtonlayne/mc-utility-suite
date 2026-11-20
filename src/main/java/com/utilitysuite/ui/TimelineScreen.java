package com.utilitysuite.ui;

import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.tools.packetlab.RateRing;
import com.utilitysuite.tools.packetlab.TimelineEvents;
import com.utilitysuite.ui.kit.ChipNode;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.UiNode;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PacketLab's unified timeline and desync view, in the menu's dark-glass style: per-second inbound/outbound packet
 * bars, chunk loads, position corrections (seconds inside a repeated-correction burst are highlighted red), lease
 * grants/ends and job changes on one time axis, plus the newest labeled events and the busiest packet types.
 * Statistics are recomputed twice a second in {@link #tick()}; rendering only reads primitive counters and cached
 * strings.
 */
public final class TimelineScreen extends Screen {

    private static final int SECONDS = 120;
    private static final int SAMPLE = 4096;
    private static final int TOP_TYPES = 6;
    private static final int EVENT_LINES = 12;
    private static final int C_IN = UiTheme.ACCENT;
    private static final int C_OUT = UiTheme.RUNNING;
    private static final int C_CHUNK = UiTheme.WARNING;
    private static final int C_CORR = UiTheme.DANGER;
    private static final int C_LEASE = 0xFFA78BFA;
    private static final int C_JOB = UiTheme.TEXT;
    private static final String[] LEGEND = {"packets in", "packets out", "chunk loads", "position corrections",
            "leases", "job changes"};
    private static final int[] LEGEND_COLORS = {C_IN, C_OUT, C_CHUNK, C_CORR, C_LEASE, C_JOB};

    private final Screen parent;
    private final PacketLab lab;
    private final long[] sTime = new long[SAMPLE];
    private final int[] sType = new int[SAMPLE];
    private final int[] sSize = new int[SAMPLE];
    private final byte[] sDir = new byte[SAMPLE];
    private final Int2IntOpenHashMap typeCounts = new Int2IntOpenHashMap();
    private final List<String> statLines = new ArrayList<>();
    private final List<String> eventLines = new ArrayList<>();
    private final List<Integer> eventColors = new ArrayList<>();
    private final List<UiNode> chrome = new ArrayList<>();
    private ChipNode modeChip;
    private String maxLabel = "1";
    private boolean frozen;
    private long frozenSecond;
    private int ticks;
    private int wx;
    private int wy;
    private int ww;
    private int wh;

    public TimelineScreen(Screen parent, PacketLab lab) {
        super(Component.literal("Packet Lab timeline"));
        this.parent = parent;
        this.lab = lab;
    }

    @Override
    protected void init() {
        ww = Math.min(760, width - 24);
        wh = Math.min(460, height - 24);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        chrome.clear();
        int y = wy + (UiTheme.TOP_BAR_H - PillButton.H) / 2;
        int x = wx + ww - 12;
        PillButton clear = new PillButton(font, "Clear", 56, PillButton.Style.DANGER_SOFT, b -> {
            lab.clearTimeline();
            recompute();
        }).pill(true);
        x -= clear.w;
        clear.x = x;
        clear.y = y;
        PillButton freeze = new PillButton(font, frozen ? "Live" : "Freeze", 60, PillButton.Style.SECONDARY, b -> {
            frozen = !frozen;
            frozenSecond = nowSecond();
            rebuildWidgets();
        }).pill(true).selected(frozen);
        x -= freeze.w + 6;
        freeze.x = x;
        freeze.y = y;
        PillButton back = new PillButton(font, "Back", 56, PillButton.Style.SECONDARY, b -> onClose()).pill(true);
        x -= back.w + 6;
        back.x = x;
        back.y = y;
        chrome.add(back);
        chrome.add(freeze);
        chrome.add(clear);
        modeChip = new ChipNode(font, frozen ? "Frozen" : "Live", frozen ? UiTheme.WARNING : UiTheme.RUNNING);
        recompute();
    }

    private static long nowSecond() {
        return System.currentTimeMillis() / 1000L;
    }

    private long endSecond() {
        return frozen ? frozenSecond : nowSecond();
    }

    @Override
    public void tick() {
        if (++ticks % 10 == 0 && !frozen) {
            recompute();
        }
    }

    private void recompute() {
        statLines.clear();
        eventLines.clear();
        eventColors.clear();
        long end = endSecond();
        RateRing pr = lab.packetRates();
        RateRing er = lab.eventRates();
        int window = 10;
        double inRate = pr.sum(end - 1, window, PacketLab.RATE_IN_COUNT) / (double) window;
        double outRate = pr.sum(end - 1, window, PacketLab.RATE_OUT_COUNT) / (double) window;
        double inKb = pr.sum(end - 1, window, PacketLab.RATE_IN_BYTES) / 1024.0 / window;
        double outKb = pr.sum(end - 1, window, PacketLab.RATE_OUT_BYTES) / 1024.0 / window;
        statLines.add(String.format(Locale.ROOT, "Last %ds: %.1f pkt/s in (%.1f KiB/s), %.1f pkt/s out (%.1f KiB/s)",
                window, inRate, inKb, outRate, outKb));
        int corr = er.sum(end, SECONDS, PacketLab.EV_CORRECTIONS);
        int bursts = lab.bursts().bursts();
        statLines.add("Chunk loads: " + er.sum(end, SECONDS, PacketLab.EV_CHUNKS) + "   Position corrections: " + corr
                + (bursts > 0 ? "   REPEATED CORRECTION BURSTS: " + bursts : "") + "   Leases: "
                + er.sum(end, SECONDS, PacketLab.EV_LEASE_GRANTS) + " granted / "
                + er.sum(end, SECONDS, PacketLab.EV_LEASE_ENDS) + " ended");
        if (!lab.isEnabled()) {
            statLines.add("Capture is off: enable Packet Lab in the Tools tab.");
        }

        int n = lab.ring().snapshot(SAMPLE, sTime, sType, sSize, sDir);
        typeCounts.clear();
        long since = (end - window) * 1000L;
        for (int i = 0; i < n; i++) {
            if (sTime[i] >= since) {
                int key = sType[i] * 2 + sDir[i];
                typeCounts.addTo(key, 1);
            }
        }
        List<int[]> top = new ArrayList<>();
        for (Int2IntOpenHashMap.Entry e : typeCounts.int2IntEntrySet()) {
            top.add(new int[]{e.getIntKey(), e.getIntValue()});
        }
        top.sort((a, b) -> Integer.compare(b[1], a[1]));
        StringBuilder sb = new StringBuilder("Busiest types: ");
        for (int i = 0; i < Math.min(TOP_TYPES, top.size()); i++) {
            int[] t = top.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(lab.types().name(t[0] / 2)).append(' ').append(t[1]); // names carry the direction
        }
        statLines.add(top.isEmpty() ? "Busiest types: -" : sb.toString());
        for (int i = 0; i < statLines.size(); i++) {
            statLines.set(i, UiRenderUtils.ellipsize(font, statLines.get(i), ww - 48));
        }

        int max = 1;
        for (int i = 0; i < SECONDS; i++) {
            long s = end - SECONDS + 1 + i;
            max = Math.max(max, Math.max(pr.get(s, PacketLab.RATE_IN_COUNT), pr.get(s, PacketLab.RATE_OUT_COUNT)));
        }
        maxLabel = max + " pkt/s";

        TimelineEvents ev = lab.events();
        long nowMs = System.currentTimeMillis();
        for (int i = ev.size() - 1; i >= 0 && eventLines.size() < EVENT_LINES; i--) {
            long t = ev.millis(i);
            eventLines.add(UiRenderUtils.ellipsize(font, String.format(Locale.ROOT, "-%5.1fs  %s",
                    (nowMs - t) / 1000.0, ev.label(i)), ww - 48));
            eventColors.add(colorOf(ev.kind(i), ev.label(i)));
        }
    }

    private static int colorOf(TimelineEvents.Kind k, String label) {
        return switch (k) {
            case CORRECTION -> label.endsWith("[REPEATED]") ? C_CORR : UiTheme.DANGER_TEXT;
            case LEASE_GRANTED, LEASE_ENDED -> C_LEASE;
            case JOB_CHANGED -> C_JOB;
            case STOP_ALL -> C_CORR;
            default -> UiTheme.TEXT_DIM;
        };
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = chrome.size() - 1; i >= 0; i--) {
            UiNode n = chrome.get(i);
            if (n.contains(mx, my, n.x, n.y) && n.interactive() && n.onClick(mx, my, button, n.x, n.y)) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, UiTheme.BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        UiRenderUtils.shadow(g, wx, wy, ww, wh, UiTheme.RADIUS, 6);
        UiRenderUtils.roundedRect(g, wx, wy, ww, wh, UiTheme.RADIUS, UiTheme.WINDOW);
        UiRenderUtils.hLine(g, wx + 1, wx + ww - 1, wy + UiTheme.TOP_BAR_H, UiTheme.BORDER);
        UiRenderUtils.roundedOutline(g, wx, wy, ww, wh, UiTheme.RADIUS, 0xFF2A2F3B);

        int ty = wy + (UiTheme.TOP_BAR_H - 8) / 2;
        UiRenderUtils.dot(g, wx + 14, ty + 1, 6, UiTheme.ACCENT);
        String title = "Packet Lab timeline";
        UiRenderUtils.text(g, font, title, wx + 24, ty, UiTheme.TEXT);
        int cx = wx + 24 + font.width(title) + 8;
        modeChip.render(g, font, cx, wy + (UiTheme.TOP_BAR_H - ChipNode.H) / 2, mouseX, mouseY);
        UiRenderUtils.text(g, font, "last " + SECONDS + " s", cx + modeChip.w + 8, ty, UiTheme.TEXT_MUTED);
        for (int i = 0; i < chrome.size(); i++) {
            UiNode n = chrome.get(i);
            n.render(g, font, n.x, n.y, mouseX, mouseY);
        }

        int pad = 12;
        int left = wx + pad;
        int cw = ww - 2 * pad;
        int y = wy + UiTheme.TOP_BAR_H + pad;

        // stats card
        int statsH = statLines.size() * 12 + 16;
        UiRenderUtils.panel(g, left, y, cw, statsH, UiTheme.RADIUS, UiTheme.CARD, UiTheme.BORDER);
        for (int i = 0; i < statLines.size(); i++) {
            String s = statLines.get(i);
            UiRenderUtils.text(g, font, s, left + 12, y + 9 + i * 12, s.contains("REPEATED") ? C_CORR
                    : i == 0 ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_DIM);
        }
        y += statsH + 8;

        // graph card
        int graphH = Math.max(40, Math.min(110, wh / 4));
        int laneH = 7;
        int cardH = 12 + graphH + 4 + 4 * (laneH + 1) + 8 + 10 + 10;
        UiRenderUtils.panel(g, left, y, cw, cardH, UiTheme.RADIUS, UiTheme.CARD, UiTheme.BORDER);
        int gl = left + 12;
        int gr = left + cw - 12;
        int gt = y + 12;
        drawGraph(g, gl, gr, gt, graphH, gt + graphH + 4, laneH);
        legend(g, gl, gt + graphH + 4 + 4 * (laneH + 1) + 8);
        y += cardH + 8;

        // events card
        int evH = wy + wh - pad - y;
        if (evH > 30) {
            UiRenderUtils.panel(g, left, y, cw, evH, UiTheme.RADIUS, UiTheme.CARD, UiTheme.BORDER);
            UiRenderUtils.text(g, font, "Recent events", left + 12, y + 9, UiTheme.TEXT);
            int ey = y + 25;
            if (eventLines.isEmpty()) {
                UiRenderUtils.text(g, font, "No events recorded yet.", left + 12, ey, UiTheme.TEXT_MUTED);
            }
            for (int i = 0; i < eventLines.size() && ey < y + evH - 10; i++) {
                UiRenderUtils.text(g, font, eventLines.get(i), left + 12, ey, eventColors.get(i));
                ey += 11;
            }
        }
    }

    private void drawGraph(GuiGraphics g, int left, int right, int top, int h, int lanesTop, int laneH) {
        RateRing pr = lab.packetRates();
        RateRing er = lab.eventRates();
        long end = endSecond();
        int w = right - left;
        float colW = w / (float) SECONDS;
        int max = 1;
        for (int i = 0; i < SECONDS; i++) {
            long s = end - SECONDS + 1 + i;
            max = Math.max(max, Math.max(pr.get(s, PacketLab.RATE_IN_COUNT), pr.get(s, PacketLab.RATE_OUT_COUNT)));
        }
        UiRenderUtils.roundedRect(g, left - 4, top - 4, w + 8, h + 8, UiTheme.RADIUS_SMALL, UiTheme.FIELD);
        for (int q = 1; q < 4; q++) {
            UiRenderUtils.hLine(g, left, right, top + h * q / 4, 0xFF1A1E26);
        }
        for (int i = 0; i < SECONDS; i++) {
            long s = end - SECONDS + 1 + i;
            int x0 = left + (int) (i * colW);
            int x1 = Math.max(x0 + 1, left + (int) ((i + 1) * colW) - 1);
            int xm = (x0 + x1) / 2;
            boolean burst = er.sum(s, 5, PacketLab.EV_CORRECTIONS) >= 3;
            if (burst) {
                g.fill(x0, top, x1 + 1, top + h, 0x40EF4444);
            }
            int in = pr.get(s, PacketLab.RATE_IN_COUNT);
            int out = pr.get(s, PacketLab.RATE_OUT_COUNT);
            int hin = in * (h - 2) / max;
            int hout = out * (h - 2) / max;
            g.fill(x0, top + h - hin, xm, top + h, C_IN);
            g.fill(xm, top + h - hout, x1, top + h, C_OUT);
            lane(g, x0, x1, lanesTop, laneH, er.get(s, PacketLab.EV_CHUNKS), C_CHUNK);
            lane(g, x0, x1, lanesTop + (laneH + 1), laneH, er.get(s, PacketLab.EV_CORRECTIONS), C_CORR);
            lane(g, x0, x1, lanesTop + 2 * (laneH + 1), laneH,
                    er.get(s, PacketLab.EV_LEASE_GRANTS) + er.get(s, PacketLab.EV_LEASE_ENDS), C_LEASE);
            lane(g, x0, x1, lanesTop + 3 * (laneH + 1), laneH, er.get(s, PacketLab.EV_JOBS), C_JOB);
        }
        UiRenderUtils.text(g, font, maxLabel, left + 2, top + 2, UiTheme.TEXT_MUTED);
    }

    private static void lane(GuiGraphics g, int x0, int x1, int y, int h, int count, int color) {
        g.fill(x0, y, x1, y + h, 0x14FFFFFF);
        if (count > 0) {
            int alpha = Math.min(255, 110 + count * 30);
            g.fill(x0, y, x1, y + h, (alpha << 24) | (color & 0xFFFFFF));
        }
    }

    private void legend(GuiGraphics g, int x, int y) {
        for (int i = 0; i < LEGEND.length; i++) {
            UiRenderUtils.roundedRect(g, x, y + 1, 7, 7, 2, LEGEND_COLORS[i]);
            UiRenderUtils.text(g, font, LEGEND[i], x + 11, y, UiTheme.TEXT_DIM);
            x += 11 + font.width(LEGEND[i]) + 12;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
