package com.utilitysuite.ui;

import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.tools.packetlab.RateRing;
import com.utilitysuite.tools.packetlab.TimelineEvents;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.kit.MenuScale;
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
 * PacketLab's unified timeline and desync view, in the window chrome of the suite menu (one hero bracket frame on
 * glass, hairline-separated bars): per-second inbound/outbound packet bars, chunk loads, position corrections
 * (seconds inside a repeated-correction burst are tinted with the danger hue), lease grants/ends and job changes on
 * one time axis, plus the newest labeled events on a vertical hairline spine (a glyph node per kind, coloured by
 * token, timestamps in fixed digit cells) and the busiest packet types.
 * Statistics are recomputed twice a second in {@link #tick()}; rendering only reads primitive counters and cached
 * strings. All colours are theme tokens.
 */
public final class TimelineScreen extends Screen {

    private static final int SECONDS = 120;
    private static final int SAMPLE = 4096;
    private static final int TOP_TYPES = 6;
    private static final int EVENT_LINES = 12;
    private static final HoloToken T_IN = HoloToken.ACCENT;
    private static final HoloToken T_OUT = HoloToken.ALLY;
    private static final HoloToken T_CHUNK = HoloToken.WARN;
    private static final HoloToken T_CORR = HoloToken.DANGER;
    private static final HoloToken T_LEASE = HoloToken.SYNC;
    private static final HoloToken T_JOB = HoloToken.TEXT;
    private static final String[] LEGEND = {"packets in", "packets out", "chunk loads", "position corrections",
            "leases", "job changes"};
    private static final HoloToken[] LEGEND_TOKENS = {T_IN, T_OUT, T_CHUNK, T_CORR, T_LEASE, T_JOB};
    /** Width of the timestamp column: "-120.0s" in 6 px digit cells. */
    private static final int TIME_W = 7 * 6;
    private static final String LIVE = "LIVE";
    private static final String FROZEN = "FROZEN";

    private final Screen parent;
    private final PacketLab lab;
    private final long[] sTime = new long[SAMPLE];
    private final int[] sType = new int[SAMPLE];
    private final int[] sSize = new int[SAMPLE];
    private final byte[] sDir = new byte[SAMPLE];
    private final Int2IntOpenHashMap typeCounts = new Int2IntOpenHashMap();
    private final List<String> statLines = new ArrayList<>();
    private final List<String> eventTimes = new ArrayList<>();
    private final List<String> eventLabels = new ArrayList<>();
    private final List<TimelineEvents.Kind> eventKinds = new ArrayList<>();
    private final List<HoloToken> eventTokens = new ArrayList<>();
    private final List<UiNode> chrome = new ArrayList<>();
    private String maxLabel = "1";
    private boolean frozen;
    private long frozenSecond;
    private int ticks;
    private int wx;
    private int wy;
    private int ww;
    private int wh;
    private String modeText = LIVE;
    private HoloToken modeToken = HoloToken.ALLY;
    private int modeW;
    private int titleW;

    public TimelineScreen(Screen parent, PacketLab lab) {
        super(Component.literal("Packet Lab timeline"));
        this.parent = parent;
        this.lab = lab;
    }

    @Override
    protected void init() {
        if (minecraft != null && minecraft.getWindow() != null) {
            double gs = minecraft.getWindow().getGuiScale();
            width = virtualSize(minecraft.getWindow().getWidth(), gs);
            height = virtualSize(minecraft.getWindow().getHeight(), gs);
        }
        ww = windowSize(760, width);
        wh = windowSize(460, height);
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
        modeText = frozen ? FROZEN : LIVE;
        modeToken = frozen ? HoloToken.WARN : HoloToken.ALLY;
        modeW = font.width(modeText) + 12;
        titleW = font.width(TITLE);
        recompute();
    }

    private static final String TITLE = "PACKET LAB TIMELINE";
    private static final String RANGE = "LAST " + SECONDS + " S";

    // ---- effective scale (same as the suite menu; engine-free helpers) ----------------------------------------------

    /** Virtual layout size for one window axis: the menu's effective scale at GUI scale 3+, vanilla below. */
    static int virtualSize(int windowPx, double guiScale) {
        return MenuScale.virtualSize(windowPx, guiScale);
    }

    /** Panel size on one axis: the preferred size, kept 12 px clear of the virtual bounds on each side. */
    static int windowSize(int preferred, int virtualExtent) {
        return Math.min(preferred, virtualExtent - 24);
    }

    /** GUI-px mouse coordinate to virtual layout px. */
    static double toVirtual(double guiCoord, double guiScale) {
        return MenuScale.toVirtual(guiCoord, guiScale);
    }

    private double guiScale() {
        return minecraft == null || minecraft.getWindow() == null ? 1.0 : minecraft.getWindow().getGuiScale();
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
        eventTimes.clear();
        eventLabels.clear();
        eventKinds.clear();
        eventTokens.clear();
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
        int labelW = ww - 24 - SPINE_X - TIME_W - 18;
        for (int i = ev.size() - 1; i >= 0 && eventLabels.size() < EVENT_LINES; i--) {
            long t = ev.millis(i);
            eventTimes.add(timeText((nowMs - t) / 1000.0));
            eventLabels.add(UiRenderUtils.ellipsize(font, ev.label(i), labelW));
            eventKinds.add(ev.kind(i));
            eventTokens.add(tokenOf(ev.kind(i), ev.label(i)));
        }
    }

    /** "-  3.2s": the age right-aligned in a fixed digit grid (the mono role; vanilla digits are tabular). */
    static String timeText(double ageSeconds) {
        String s = String.format(Locale.ROOT, "-%.1fs", ageSeconds);
        StringBuilder pad = new StringBuilder();
        for (int i = s.length(); i < 7; i++) {
            pad.append(' ');
        }
        return pad.append(s).toString();
    }

    static HoloToken tokenOf(TimelineEvents.Kind k, String label) {
        return switch (k) {
            case CORRECTION, STOP_ALL -> T_CORR;
            case LEASE_GRANTED, LEASE_ENDED -> T_LEASE;
            case JOB_CHANGED -> T_JOB;
            case CHUNK_BURST -> T_CHUNK;
            default -> HoloToken.TEXT_MUTED;
        };
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double guiX, double guiY, int button) {
        double gs = guiScale();
        double mx = toVirtual(guiX, gs);
        double my = toVirtual(guiY, gs);
        for (int i = chrome.size() - 1; i >= 0; i--) {
            UiNode n = chrome.get(i);
            if (n.contains(mx, my, n.x, n.y) && n.interactive() && n.onClick(mx, my, button, n.x, n.y)) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }
        return super.mouseClicked(guiX, guiY, button);
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        HoloFrames.plate(g, 0, 0, width, height, HoloToken.VOID, 0.55f);
    }

    @Override
    public void render(GuiGraphics g, int guiMouseX, int guiMouseY, float partialTick) {
        double gs = guiScale();
        float f = (float) MenuScale.factor(gs);
        if (f == 1f) {
            renderScaled(g, guiMouseX, guiMouseY, partialTick);
            return;
        }
        g.pose().pushPose();
        g.pose().scale(f, f, 1f);
        MenuScale.setActive(f);
        try {
            renderScaled(g, (int) toVirtual(guiMouseX, gs), (int) toVirtual(guiMouseY, gs), partialTick);
        } finally {
            MenuScale.reset();
            g.pose().popPose();
        }
    }

    /** Horizontal offset of the spine from the card's left edge. */
    private static final int SPINE_X = 20;

    private void renderScaled(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        // the menu's window chrome: dark base, hero bracket frame on glass, scanlines, hairline under the title bar
        HoloFrames.frame(g, wx, wy, ww, wh, FrameLevel.QUIET, true, HoloToken.VOID, HoloToken.LINE, 0.62f);
        HoloFrames.frame(g, wx, wy, ww, wh, FrameLevel.HERO, true, 1f);
        HoloFrames.scanlines(g, wx, wy, ww, wh, 1f);
        int faint = HoloTheme.argb(HoloToken.LINE_FAINT);
        HoloFrames.hLine(g, wx + 1, wx + ww - 1, wy + UiTheme.TOP_BAR_H, faint);

        int ty = wy + (UiTheme.TOP_BAR_H - 8) / 2;
        HoloFrames.sideBar(g, wx + 12, ty - 1, 10, HoloToken.ACCENT, 1f);
        UiRenderUtils.text(g, font, TITLE, wx + 20, ty, HoloTheme.argb(HoloToken.TEXT));
        int rx = wx + 20 + titleW + 8;
        readoutBrackets(g, rx, ty - 3, modeW, 14, HoloTheme.argb(HoloToken.LINE));
        UiRenderUtils.text(g, font, modeText, rx + 6, ty, HoloTheme.argb(modeToken));
        UiRenderUtils.text(g, font, RANGE, rx + modeW + 8, ty, HoloTheme.argb(HoloToken.TEXT_MUTED));
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
        card(g, left, y, cw, statsH);
        for (int i = 0; i < statLines.size(); i++) {
            String s = statLines.get(i);
            HoloToken t = s.contains("REPEATED") ? T_CORR : i == 0 ? HoloToken.TEXT_SECONDARY : HoloToken.TEXT_MUTED;
            UiRenderUtils.text(g, font, s, left + 12, y + 9 + i * 12, HoloTheme.argb(t));
        }
        y += statsH + 8;

        // graph card
        int graphH = Math.max(40, Math.min(110, wh / 4));
        int laneH = 7;
        int cardH = 12 + graphH + 4 + 4 * (laneH + 1) + 8 + 10 + 10;
        card(g, left, y, cw, cardH);
        int gl = left + 12;
        int gr = left + cw - 12;
        int gt = y + 12;
        drawGraph(g, gl, gr, gt, graphH, gt + graphH + 4, laneH);
        legend(g, gl, gt + graphH + 4 + 4 * (laneH + 1) + 8);
        y += cardH + 8;

        // events card: a vertical hairline spine with a glyph node per event
        int evH = wy + wh - pad - y;
        if (evH > 30) {
            card(g, left, y, cw, evH);
            smallCaption(g, "RECENT EVENTS", left + 12, y + 9);
            int ey = y + 25;
            int spine = left + SPINE_X;
            if (eventLabels.isEmpty()) {
                UiRenderUtils.text(g, font, "No events recorded yet.", left + 12, ey,
                        HoloTheme.argb(HoloToken.TEXT_MUTED));
            } else {
                int bottom = Math.min(y + evH - 8, ey + eventLabels.size() * 11);
                HoloFrames.vLine(g, spine, ey - 2, bottom, HoloTheme.argb(HoloToken.LINE));
            }
            for (int i = 0; i < eventLabels.size() && ey < y + evH - 10; i++) {
                HoloToken t = eventTokens.get(i);
                node(g, spine, ey + 4, eventKinds.get(i), t);
                UiRenderUtils.text(g, font, eventTimes.get(i), spine + 10, ey, HoloTheme.argb(HoloToken.TEXT_MUTED));
                UiRenderUtils.text(g, font, eventLabels.get(i), spine + 10 + TIME_W + 8, ey,
                        HoloTheme.argb(t == HoloToken.TEXT_MUTED ? HoloToken.TEXT_SECONDARY : t));
                ey += 11;
            }
        }
    }

    /** A quiet glass card: translucent raised glass with a faint hairline edge and no brackets. */
    private static void card(GuiGraphics g, int x, int y, int w, int h) {
        HoloFrames.frame(g, x, y, w, h, FrameLevel.QUIET, false, HoloToken.GLASS_RAISED, HoloToken.LINE, 0.5f);
    }

    private void smallCaption(GuiGraphics g, String s, int x, int y) {
        UiRenderUtils.text(g, font, s, x, y, HoloTheme.argb(HoloToken.ACCENT, 0.85f));
        HoloFrames.hLine(g, x + font.width(s) + 6, x + 200, y + 4, HoloTheme.argb(HoloToken.LINE_FAINT));
    }

    /** Square brackets around a readout, as hairlines (no fill). */
    private static void readoutBrackets(GuiGraphics g, int x, int y, int w, int h, int argb) {
        HoloFrames.vLine(g, x, y, y + h, argb);
        HoloFrames.hLine(g, x + 1, x + 3, y, argb);
        HoloFrames.hLine(g, x + 1, x + 3, y + h - 1, argb);
        HoloFrames.vLine(g, x + w - 1, y, y + h, argb);
        HoloFrames.hLine(g, x + w - 3, x + w - 1, y, argb);
        HoloFrames.hLine(g, x + w - 3, x + w - 1, y + h - 1, argb);
    }

    /** The glyph node of an event, centred on (cx, cy), 5 px: a diamond for leases, a cross for corrections ... */
    private static void node(GuiGraphics g, int cx, int cy, TimelineEvents.Kind kind, HoloToken token) {
        switch (kind) {
            case LEASE_GRANTED, LEASE_ENDED -> {
                float a = kind == TimelineEvents.Kind.LEASE_ENDED ? 0.6f : 1f;
                HoloFrames.plate(g, cx, cy - 2, 1, 1, token, a);
                HoloFrames.plate(g, cx - 1, cy - 1, 3, 1, token, a);
                HoloFrames.plate(g, cx - 2, cy, 5, 1, token, a);
                HoloFrames.plate(g, cx - 1, cy + 1, 3, 1, token, a);
                HoloFrames.plate(g, cx, cy + 2, 1, 1, token, a);
            }
            case CORRECTION -> {
                for (int i = 0; i < 5; i++) {
                    HoloFrames.plate(g, cx - 2 + i, cy - 2 + i, 1, 1, token, 1f);
                    HoloFrames.plate(g, cx + 2 - i, cy - 2 + i, 1, 1, token, 1f);
                }
            }
            case STOP_ALL -> HoloFrames.plate(g, cx - 2, cy - 2, 5, 5, token, 1f);
            case JOB_CHANGED -> HoloFrames.plate(g, cx - 1, cy - 1, 3, 3, token, 1f);
            case CHUNK_BURST -> {
                HoloFrames.plate(g, cx - 2, cy - 2, 5, 1, token, 1f);
                HoloFrames.plate(g, cx - 2, cy + 2, 5, 1, token, 1f);
                HoloFrames.plate(g, cx - 2, cy - 1, 1, 3, token, 1f);
                HoloFrames.plate(g, cx + 2, cy - 1, 1, 3, token, 1f);
            }
            default -> HoloFrames.plate(g, cx - 1, cy - 1, 3, 3, token, 0.6f);
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
        HoloFrames.plate(g, left - 4, top - 4, w + 8, h + 8, HoloToken.FIELD, 1f);
        int grid = HoloTheme.argb(HoloToken.LINE_FAINT, 0.6f);
        for (int q = 1; q < 4; q++) {
            HoloFrames.hLine(g, left, right, top + h * q / 4, grid);
        }
        for (int i = 0; i < SECONDS; i++) {
            long s = end - SECONDS + 1 + i;
            int x0 = left + (int) (i * colW);
            int x1 = Math.max(x0 + 1, left + (int) ((i + 1) * colW) - 1);
            int xm = (x0 + x1) / 2;
            boolean burst = er.sum(s, 5, PacketLab.EV_CORRECTIONS) >= 3;
            if (burst) {
                HoloFrames.plate(g, x0, top, x1 + 1 - x0, h, T_CORR, 0.25f);
            }
            int in = pr.get(s, PacketLab.RATE_IN_COUNT);
            int out = pr.get(s, PacketLab.RATE_OUT_COUNT);
            int hin = in * (h - 2) / max;
            int hout = out * (h - 2) / max;
            HoloFrames.plate(g, x0, top + h - hin, xm - x0, hin, T_IN, 1f);
            HoloFrames.plate(g, xm, top + h - hout, x1 - xm, hout, T_OUT, 1f);
            lane(g, x0, x1, lanesTop, laneH, er.get(s, PacketLab.EV_CHUNKS), T_CHUNK);
            lane(g, x0, x1, lanesTop + (laneH + 1), laneH, er.get(s, PacketLab.EV_CORRECTIONS), T_CORR);
            lane(g, x0, x1, lanesTop + 2 * (laneH + 1), laneH,
                    er.get(s, PacketLab.EV_LEASE_GRANTS) + er.get(s, PacketLab.EV_LEASE_ENDS), T_LEASE);
            lane(g, x0, x1, lanesTop + 3 * (laneH + 1), laneH, er.get(s, PacketLab.EV_JOBS), T_JOB);
        }
        UiRenderUtils.text(g, font, maxLabel, left + 2, top + 2, HoloTheme.argb(HoloToken.TEXT_MUTED));
    }

    private static void lane(GuiGraphics g, int x0, int x1, int y, int h, int count, HoloToken token) {
        HoloFrames.plate(g, x0, y, x1 - x0, h, HoloToken.LINE_FAINT, 0.35f);
        if (count > 0) {
            HoloFrames.plate(g, x0, y, x1 - x0, h, token, Math.min(1f, (110 + count * 30) / 255f));
        }
    }

    private void legend(GuiGraphics g, int x, int y) {
        for (int i = 0; i < LEGEND.length; i++) {
            HoloFrames.plate(g, x, y + 2, 5, 5, LEGEND_TOKENS[i], 1f);
            UiRenderUtils.text(g, font, LEGEND[i], x + 11, y, HoloTheme.argb(HoloToken.TEXT_SECONDARY));
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
