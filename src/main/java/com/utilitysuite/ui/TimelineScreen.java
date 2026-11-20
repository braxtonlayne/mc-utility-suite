package com.utilitysuite.ui;

import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.tools.packetlab.PacketRingBuffer;
import com.utilitysuite.tools.packetlab.RateRing;
import com.utilitysuite.tools.packetlab.TimelineEvents;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PacketLab's unified timeline and desync view: per-second inbound/outbound packet bars, chunk loads, position
 * corrections (seconds inside a repeated-correction burst are highlighted red), lease grants/ends and job changes on
 * one time axis, plus the newest labeled events and the busiest packet types. Statistics are recomputed twice a
 * second in {@link #tick()}; rendering only reads primitive counters and cached strings.
 */
public final class TimelineScreen extends Screen {

    private static final int SECONDS = 120;
    private static final int SAMPLE = 4096;
    private static final int TOP_TYPES = 6;
    private static final int EVENT_LINES = 12;
    private static final int C_IN = 0xFF4FA3FF;
    private static final int C_OUT = 0xFF6BD66B;
    private static final int C_CHUNK = 0xFFE8C547;
    private static final int C_CORR = 0xFFFF5555;
    private static final int C_LEASE = 0xFFC77DFF;
    private static final int C_JOB = 0xFFFFFFFF;

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
    private boolean frozen;
    private long frozenSecond;
    private int ticks;

    public TimelineScreen(Screen parent, PacketLab lab) {
        super(Component.literal("Packet Lab timeline"));
        this.parent = parent;
        this.lab = lab;
    }

    @Override
    protected void init() {
        int y = height - 26;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 - 154, y, 100, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal(frozen ? "Live" : "Freeze"), b -> {
            frozen = !frozen;
            frozenSecond = nowSecond();
            rebuildWidgets();
        }).bounds(width / 2 - 50, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            lab.clearTimeline();
            recompute();
        }).bounds(width / 2 + 54, y, 100, 20).build());
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

        TimelineEvents ev = lab.events();
        long nowMs = System.currentTimeMillis();
        for (int i = ev.size() - 1; i >= 0 && eventLines.size() < EVENT_LINES; i--) {
            long t = ev.millis(i);
            eventLines.add(String.format(Locale.ROOT, "-%5.1fs  %s", (nowMs - t) / 1000.0, ev.label(i)));
            eventColors.add(colorOf(ev.kind(i), ev.label(i)));
        }
    }

    private static int colorOf(TimelineEvents.Kind k, String label) {
        return switch (k) {
            case CORRECTION -> label.endsWith("[REPEATED]") ? C_CORR : 0xFFFF9C9C;
            case LEASE_GRANTED, LEASE_ENDED -> C_LEASE;
            case JOB_CHANGED -> C_JOB;
            case STOP_ALL -> C_CORR;
            default -> 0xFFB0B0B0;
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int left = 12;
        int right = width - 12;
        g.drawString(font, title.getString() + (frozen ? "  (frozen)" : "") + "  -  last " + SECONDS + " s",
                left, 8, 0xFFFFFFFF);
        int y = 22;
        for (String s : statLines) {
            g.drawString(font, s, left, y, s.contains("REPEATED") ? C_CORR : 0xFFE0E0E0);
            y += 10;
        }
        y += 4;
        int graphTop = y;
        int graphH = Math.max(40, Math.min(110, height / 3));
        int laneH = 7;
        int lanesTop = graphTop + graphH + 2;
        drawGraph(g, left, right, graphTop, graphH, lanesTop, laneH);
        int legendY = lanesTop + 4 * (laneH + 1) + 4;
        legend(g, left, legendY);
        int ey = legendY + 14;
        for (int i = 0; i < eventLines.size() && ey < height - 34; i++) {
            g.drawString(font, eventLines.get(i), left, ey, eventColors.get(i));
            ey += 10;
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
        g.fill(left, top, right, top + h, 0x80000000);
        for (int i = 0; i < SECONDS; i++) {
            long s = end - SECONDS + 1 + i;
            int x0 = left + (int) (i * colW);
            int x1 = Math.max(x0 + 1, left + (int) ((i + 1) * colW) - 1);
            int xm = (x0 + x1) / 2;
            boolean burst = er.sum(s, 5, PacketLab.EV_CORRECTIONS) >= 3;
            if (burst) {
                g.fill(x0, top, x1 + 1, top + h, 0x50FF2020);
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
        g.drawString(font, Integer.toString(max), left + 2, top + 2, 0xFFA0A0A0, false);
    }

    private static void lane(GuiGraphics g, int x0, int x1, int y, int h, int count, int color) {
        g.fill(x0, y, x1, y + h, 0x30FFFFFF);
        if (count > 0) {
            int alpha = Math.min(255, 110 + count * 30);
            g.fill(x0, y, x1, y + h, (alpha << 24) | (color & 0xFFFFFF));
        }
    }

    private void legend(GuiGraphics g, int x, int y) {
        x = legendItem(g, x, y, C_IN, "packets in");
        x = legendItem(g, x, y, C_OUT, "packets out");
        x = legendItem(g, x, y, C_CHUNK, "chunk loads");
        x = legendItem(g, x, y, C_CORR, "position corrections");
        x = legendItem(g, x, y, C_LEASE, "leases");
        legendItem(g, x, y, C_JOB, "job changes");
    }

    private int legendItem(GuiGraphics g, int x, int y, int color, String label) {
        g.fill(x, y + 1, x + 7, y + 8, color);
        g.drawString(font, label, x + 10, y, 0xFFC0C0C0, false);
        return x + 10 + font.width(label) + 10;
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
