package com.utilitysuite.world.replay;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * The replay theatre screen: the frozen 30 s scene drawn as an orbit view (drag to orbit, scroll to zoom) around the
 * local player at the playhead: entity boxes coloured by kind (you cyan, hostile red, players orange), hurt flashes,
 * velocity vectors (10 ticks ahead), look lines while swinging, hit markers (X) near hit events and a ground grid. The
 * bottom strip is the timeline (click/drag to scrub) with hit ticks marked, play state, speed and the trade timing.
 * Keys: Space play/pause, Left/Right step a tick, Up/Down speed, I sets the in-point, G saves in-point..playhead of
 * your own track as the parkour ghost (and saves it with the course), K saves the 30 s ring as a kill-cam clip on disk,
 * Esc closes (recording resumes). The same screen opens a saved clip read back from disk.
 * <p>The view is a projected wireframe (a {@link ViewProjector}), not a second world render; see the PiP notes.
 */
public final class ReplayScreen extends Screen {

    private static final int COLOR_SELF = 0xFF50E0FF;
    private static final int COLOR_HOSTILE = 0xFFFF5050;
    private static final int COLOR_PLAYER = 0xFFFFA040;
    private static final int COLOR_OTHER = 0xFFB0B0B0;
    private static final int COLOR_HURT = 0xFFFFFFFF;
    private static final int COLOR_VEL = 0xFF60FF60;
    private static final int COLOR_LOOK = 0xFFFFE040;
    private static final int COLOR_GRID = 0x40FFFFFF;
    private static final int BAR_H = 44;

    private final ReplayTheatreModule module;
    private final ReplayRing ring;
    private final ReplayTimeline timeline = new ReplayTimeline();
    private final ViewProjector view = new ViewProjector();
    private final GuiLines lines = new GuiLines();
    private final double[] pose = new double[5];
    private final double[] center = new double[5];
    private final float[] segs = new float[ReplayRing.SLOTS * 4 * 16 + 256];
    private final float[] pt = new float[3];

    private float orbitYaw;
    private float orbitPitch = 35f;
    private double distance = 12;
    private boolean dragOrbit;
    private boolean dragBar;
    private long lastNanos;
    private int inPoint;
    private String status = "";
    private final int selfId;
    private final boolean savedClip;

    /** The live (frozen) ring, with the local player as "you". */
    public ReplayScreen(ReplayTheatreModule module) {
        this(module, module.ring(), localPlayerId(), false);
    }

    private static int localPlayerId() {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        return p == null ? Integer.MIN_VALUE : p.getId();
    }

    /**
     * @param ring      the frozen live ring, or a ring decoded from a saved clip
     * @param selfId    the entity id of "you" in that ring (the local player when it was recorded)
     * @param savedClip true for a clip read from disk (closing does not touch the live recording)
     */
    public ReplayScreen(ReplayTheatreModule module, ReplayRing ring, int selfId, boolean savedClip) {
        super(Component.literal(savedClip ? "Replay Theatre (saved clip)" : "Replay Theatre"));
        this.module = module;
        this.ring = ring;
        this.selfId = selfId;
        this.savedClip = savedClip;
        timeline.load(ring.frames());
        if (ring.pose(timeline.position(), selfId, pose)) {
            orbitYaw = (float) pose[3];
        }
    }

    private int selfId() {
        return selfId;
    }

    /** Called whenever this screen goes away (Esc, another screen, disconnect): recording resumes. */
    @Override
    public void removed() {
        if (!savedClip) {
            module.unfreeze();
        }
        super.removed();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xD0101418);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : (now - lastNanos) * 1e-9;
        lastNanos = now;
        timeline.advance(Math.min(dt, 0.25));
        if (ring.frames() == 0) {
            return;
        }
        int viewH = height - BAR_H;
        double t = timeline.position();
        int self = selfId();
        if (!ring.pose(t, self, center)) {
            center[0] = center[1] = center[2] = 0;
        }
        view.setOrbit(center[0], center[1] + 1.0, center[2], orbitYaw, orbitPitch, distance, 70, width / (double) viewH);
        int n = 0;
        Matrix4f m = g.pose().last().pose();
        lines.begin(m, 0, 0, width, viewH);
        // ground grid
        double gy = Math.floor(center[1]);
        int cx = (int) Math.floor(center[0]);
        int cz = (int) Math.floor(center[2]);
        for (int k = -8; k <= 8; k++) {
            n = view.projectSegment(cx + k, gy, cz - 8, cx + k, gy, cz + 8, segs, 0);
            if (n > 0) {
                lines.segment(segs[0], segs[1], segs[2], segs[3], 1f, COLOR_GRID);
            }
            n = view.projectSegment(cx - 8, gy, cz + k, cx + 8, gy, cz + k, segs, 0);
            if (n > 0) {
                lines.segment(segs[0], segs[1], segs[2], segs[3], 1f, COLOR_GRID);
            }
        }
        int frame = (int) Math.floor(t);
        int count = ring.count(frame);
        for (int s = 0; s < count; s++) {
            int i = ring.at(frame, s);
            int id = ring.id(i);
            if (!ring.pose(t, id, pose)) {
                continue;
            }
            byte f = ring.flags(i);
            int color = (f & ReplayRing.F_SELF) != 0 ? COLOR_SELF
                    : (f & ReplayRing.F_HOSTILE) != 0 ? COLOR_HOSTILE
                    : (f & ReplayRing.F_PLAYER) != 0 ? COLOR_PLAYER : COLOR_OTHER;
            if ((f & ReplayRing.F_HURT) != 0) {
                color = COLOR_HURT;
            }
            double hw = ring.width(i) * 0.5;
            double h = ring.height(i);
            int nb = view.projectBox(pose[0] - hw, pose[1], pose[2] - hw, pose[0] + hw, pose[1] + h, pose[2] + hw,
                    segs, 0);
            n = view.projectSegment(pose[0], pose[1] + h * 0.5, pose[2], pose[0] + ring.vx(i) * 10,
                    pose[1] + h * 0.5 + ring.vy(i) * 10, pose[2] + ring.vz(i) * 10, segs, nb);
            boolean vel = n > nb;
            for (int k = 0; k + 3 < n; k += 4) {
                boolean isVel = vel && k == n - 4;
                lines.segment(segs[k], segs[k + 1], segs[k + 2], segs[k + 3], isVel ? 1.5f : 1.2f,
                        isVel ? COLOR_VEL : color);
            }
            if ((f & ReplayRing.F_SWING) != 0) {
                double ya = Math.toRadians(pose[3]);
                double pa = Math.toRadians(pose[4]);
                double eye = h * 0.85;
                n = view.projectSegment(pose[0], pose[1] + eye, pose[2], pose[0] - Math.sin(ya) * Math.cos(pa) * 2.5,
                        pose[1] + eye - Math.sin(pa) * 2.5, pose[2] + Math.cos(ya) * Math.cos(pa) * 2.5, segs, 0);
                if (n > 0) {
                    lines.segment(segs[0], segs[1], segs[2], segs[3], 1.5f, COLOR_LOOK);
                }
            }
        }
        // hit markers within half a second of the playhead
        long nowTick = ring.tickOf(frame);
        for (int e = 0; e < ring.events(); e++) {
            long et = ring.eventTick(e);
            if (Math.abs(et - nowTick) > 10 || ring.eventType(e) == ReplayRing.EV_NOTE) {
                continue;
            }
            int who = ring.eventEntity(e);
            if (!ring.pose(t, who, pose) || !view.project(pose[0], pose[1] + 1.0, pose[2], pt)) {
                continue;
            }
            float x = lines.sx(pt[0]);
            float y = lines.sy(pt[1]);
            int c = ring.eventType(e) == ReplayRing.EV_HIT_TAKEN ? 0xFFFF4040 : 0xFF40FF40;
            lines.pixelSegment(x - 6, y - 6, x + 6, y + 6, 2f, c);
            lines.pixelSegment(x - 6, y + 6, x + 6, y - 6, 2f, c);
        }
        lines.end();
        drawTimeline(g, viewH);
        drawText(g, viewH, nowTick);
    }

    private void drawTimeline(GuiGraphics g, int viewH) {
        int x0 = 10;
        int x1 = width - 10;
        int y = viewH + 8;
        g.fill(0, viewH, width, height, 0xE0080A0E);
        g.fill(x0, y, x1, y + 6, 0xFF303840);
        int px = x0 + (int) Math.round(timeline.fraction() * (x1 - x0));
        g.fill(x0, y, px, y + 6, 0xFF4080C0);
        long first = ring.tickOf(0);
        long last = ring.tickOf(ring.frames() - 1);
        double span = Math.max(1, last - first);
        for (int e = 0; e < ring.events(); e++) {
            long et = ring.eventTick(e);
            if (et < first || et > last) {
                continue;
            }
            int ex = x0 + (int) Math.round((et - first) / span * (x1 - x0));
            byte ty = ring.eventType(e);
            int c = ty == ReplayRing.EV_HIT_TAKEN ? 0xFFFF4040 : ty == ReplayRing.EV_HIT_DEALT ? 0xFF40FF40 : 0xFFFFE040;
            g.fill(ex, y - 3, ex + 1, y + 9, c);
        }
        if (inPoint > 0) {
            int ix = x0 + (int) Math.round(inPoint / Math.max(1.0, ring.frames() - 1.0) * (x1 - x0));
            g.fill(ix, y - 4, ix + 1, y + 10, 0xFFFFE040);
        }
        g.fill(px - 1, y - 4, px + 2, y + 10, 0xFFFFFFFF);
    }

    private void drawText(GuiGraphics g, int viewH, long nowTick) {
        StringBuilder b = new StringBuilder(96);
        b.append('-');
        ReplayTheatreModule.appendSeconds(b, timeline.secondsBeforeEnd() * ReplayTimeline.TICKS_PER_SECOND);
        b.append("   x").append(timeline.speed()).append(timeline.playing() ? "   playing" : "   paused");
        int gap = ring.tradeGap(nowTick);
        if (gap != Integer.MIN_VALUE) {
            b.append("   trade: ");
            if (gap >= 0) {
                b.append("you hit first by ").append(gap).append(" t");
            } else {
                b.append("they hit first by ").append(-gap).append(" t");
            }
        }
        g.drawString(font, b.toString(), 10, viewH + 20, 0xFFE0E0E0, false);
        g.drawString(font, "Space play  ←/→ step  ↑/↓ speed  drag orbit  scroll zoom  I in-point  "
                + "G save ghost  " + (savedClip ? "" : "K save clip  ") + "Esc close" + status, 10, viewH + 31, 0xFF8090A0, false);
    }

    private void scrubAt(double mouseX) {
        double f = (mouseX - 10) / Math.max(1.0, width - 20.0);
        timeline.scrubTo(f);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= height - BAR_H) {
            dragBar = true;
            scrubAt(mouseX);
            return true;
        }
        if (button == 0) {
            dragOrbit = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragOrbit = false;
        dragBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragBar) {
            scrubAt(mouseX);
            return true;
        }
        if (dragOrbit) {
            orbitYaw += (float) dx * 0.5f;
            orbitPitch = Math.max(-89f, Math.min(89f, orbitPitch + (float) dy * 0.5f));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double sx, double sy) {
        distance = Math.max(3, Math.min(48, distance * (sy > 0 ? 0.88 : 1.14)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_SPACE -> timeline.togglePlay();
            case GLFW.GLFW_KEY_LEFT -> timeline.step(-1);
            case GLFW.GLFW_KEY_RIGHT -> timeline.step(1);
            case GLFW.GLFW_KEY_UP -> timeline.faster();
            case GLFW.GLFW_KEY_DOWN -> timeline.slower();
            case GLFW.GLFW_KEY_I -> inPoint = (int) Math.round(timeline.position());
            case GLFW.GLFW_KEY_G -> {
                int n = module.saveGhost(ring, selfId, inPoint, (int) Math.round(timeline.position()));
                status = n > 1 ? "   ghost saved (" + n + " ticks)" : "   nothing to save";
            }
            case GLFW.GLFW_KEY_K -> status = savedClip ? "   already a saved clip"
                    : "   " + module.saveClip(ring, selfId);
            default -> {
                return super.keyPressed(key, scan, mods);
            }
        }
        return true;
    }
}
