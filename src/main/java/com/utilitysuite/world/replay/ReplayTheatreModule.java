package com.utilitysuite.world.replay;

import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Replay Theatre (F2, merges C9): while enabled, every client tick records the local player and up to
 * {@code max_entities} nearby entities (position, look, velocity, box, hurt/swing flags) into a 30 s
 * {@link ReplayRing}, plus hit events (taken: your hurt timer restarting; dealt: a nearby entity's hurt timer restarting
 * within 3 ticks of your swing). The freeze key stops the recording and opens {@link ReplayScreen} (orbit view,
 * timeline, play/pause/speed, entity boxes with velocity vectors, hit markers, trade timing, "save as ghost").
 * <p>Parkour splits: two keys mark the start and finish at your feet; runs between them are timed ({@link ParkourSplits})
 * and the personal best becomes a translucent ghost box in the world (or the scene saved from the replay screen). While
 * a run is live, one HUD line shows the split delta vs the ghost. Recording is allocation free (preallocated arrays;
 * entity iteration uses the level's own iterable). Off by default; nothing is drawn when idle or in Clean-Screen.
 */
public final class ReplayTheatreModule extends WorldModule implements LayeredDraw.Layer {

    public static final String ID = "replay_theatre";

    private final ReplayRing ring = new ReplayRing();
    private final ParkourSplits splits = new ParkourSplits();
    private final double[] ghostPos = new double[3];
    private final double[] trackX = new double[ReplayRing.FRAMES];
    private final double[] trackY = new double[ReplayRing.FRAMES];
    private final double[] trackZ = new double[ReplayRing.FRAMES];
    private final StringBuilder line = new StringBuilder(48);

    private KeyMapping freezeKey;
    private KeyMapping startKey;
    private KeyMapping finishKey;

    private double radius = 16;
    private int maxEntities = 8;
    private boolean showGhost = true;
    private double splitRadius = 1.5;
    private int lastSelfHurt;
    private int lastSwingTick = -100;
    private long tickCounter;
    private boolean everFinished;
    private long cachedTick = -1;
    private long lastHandOver = Long.MIN_VALUE;
    private String cached = "";

    public ReplayTheatreModule() {
        super(ID, "Replay Theatre", false);
    }

    public void setKeys(KeyMapping freeze, KeyMapping start, KeyMapping finish) {
        freezeKey = freeze;
        startKey = start;
        finishKey = finish;
    }

    public ReplayRing ring() {
        return ring;
    }

    public ParkourSplits splits() {
        return splits;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal("radius", "Record radius", 16.0, 4.0, 48.0, "Recording",
                        "Entities within this many blocks of you are recorded with you."),
                SettingSpec.integer("max_entities", "Entities kept", 8, 1, ReplayRing.SLOTS - 1, "Recording",
                        "Nearby entities recorded per tick besides you (first found within the radius)."),
                SettingSpec.bool("show_ghost", "Show ghost", true, "Parkour ghost",
                        "Draw the translucent ghost of your best (or saved) run between the parkour markers."),
                SettingSpec.decimal("split_radius", "Marker radius", 1.5, 0.5, 4.0, "Parkour ghost",
                        "How close to the start/finish marker counts as standing on it."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        radius = decimal(p, "radius");
        maxEntities = integer(p, "max_entities");
        showGhost = bool(p, "show_ghost");
        splitRadius = decimal(p, "split_radius");
        splits.setRadius(splitRadius);
    }

    @Override
    protected void onDisable() {
        ring.clear();
        splits.resetClock();
    }

    @Override
    public void onWorldUnload() {
        ring.clear();
        splits.resetClock();
    }

    /** Client tick (game bus, always): key presses; the recording itself runs in {@link #onTick}. */
    public void pollKeys() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        while (freezeKey != null && freezeKey.consumeClick()) {
            if (p != null && isEnabled() && ring.frames() > 1 && mc.screen == null) {
                ring.setFrozen(true);
                mc.setScreen(new ReplayScreen(this));
            }
        }
        while (startKey != null && startKey.consumeClick()) {
            if (p != null && isEnabled()) {
                splits.setStart(p.getX(), p.getY(), p.getZ());
            }
        }
        while (finishKey != null && finishKey.consumeClick()) {
            if (p != null && isEnabled()) {
                splits.setFinish(p.getX(), p.getY(), p.getZ());
            }
        }
    }

    /** Called by the replay screen when it closes. */
    void unfreeze() {
        ring.setFrozen(false);
    }

    /** Replay screen "save as ghost": the local player's track over [from, to] frames becomes the ghost. */
    int saveGhost(int fromFrame, int toFrame) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return 0;
        }
        int n = ring.copyTrack(mc.player.getId(), fromFrame, toFrame, trackX, trackY, trackZ);
        if (n > 1 && !splits.ready()) {
            // no markers yet: the saved stretch's ends become the start and finish
            splits.setStart(trackX[0], trackY[0], trackZ[0]);
            splits.setFinish(trackX[n - 1], trackY[n - 1], trackZ[n - 1]);
        }
        if (n > 1) {
            splits.setGhost(trackX, trackY, trackZ, n);
            splits.setBestTicks(n - 1);
        }
        return n;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            return;
        }
        tickCounter++;
        if (splits.ready()) {
            if (splits.tick(p.getX(), p.getY(), p.getZ())) {
                everFinished = true;
            }
        }
        if (ring.frozen()) {
            return;
        }
        long t = tickCounter;
        ring.beginFrame(t);
        int selfId = p.getId();
        if (p.swinging && p.swingTime == 0) {
            lastSwingTick = (int) t;
        }
        // hit taken
        if (p.hurtTime > lastSelfHurt && p.hurtTime > 0) {
            ring.addEvent(t, ReplayRing.EV_HIT_TAKEN, p.getHealth(), selfId);
        }
        lastSelfHurt = p.hurtTime;
        // autopilot hand-over starts (through core.api; the BlackBox rows themselves are not exposed there)
        IAutopilot ap = Services.autopilotOrNull();
        long ho = ap == null ? -1 : ap.handOverStartTick();
        if (ho != lastHandOver) {
            if (ho >= 0 && lastHandOver != Long.MIN_VALUE) {
                ring.addEvent(t, ReplayRing.EV_NOTE, 0f, selfId);
            }
            lastHandOver = ho;
        }
        add(p, (byte) (ReplayRing.F_SELF | ReplayRing.F_PLAYER));
        double r2 = radius * radius;
        int kept = 0;
        for (Entity e : level.entitiesForRendering()) {
            if (kept >= maxEntities) {
                break;
            }
            if (e == p || e.isRemoved() || e.distanceToSqr(p) > r2) {
                continue;
            }
            byte f = 0;
            if (e instanceof Player) {
                f |= ReplayRing.F_PLAYER;
            }
            if (e instanceof Enemy) {
                f |= ReplayRing.F_HOSTILE;
            }
            if (e instanceof LivingEntity le && le.hurtTime > 0 && le.hurtTime >= le.hurtDuration - 1) {
                // fresh hit on this entity; credit it to you when you swung within 3 ticks and were in reach
                if (t - lastSwingTick <= 3 && e.distanceToSqr(p) < 36.0) {
                    ring.addEvent(t, ReplayRing.EV_HIT_DEALT, le.getHealth(), e.getId());
                }
            }
            add(e, f);
            kept++;
        }
    }

    private void add(Entity e, byte base) {
        byte f = base;
        if (e instanceof LivingEntity le) {
            if (le.hurtTime > 0) {
                f |= ReplayRing.F_HURT;
            }
            if (le.swinging) {
                f |= ReplayRing.F_SWING;
            }
        }
        Vec3 v = e.getDeltaMovement(); // existing field read, not allocated
        ring.addEntity(e.getId(), e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot(), (float) v.x, (float) v.y,
                (float) v.z, e.getBbWidth(), e.getBbHeight(), f);
    }

    long currentTick() {
        return tickCounter;
    }

    // ---- world: markers and ghost -------------------------------------------------------------------------------

    @Override
    public void renderLines(RenderContext ctx) {
        if (!splits.ready() || !showGhost) {
            return;
        }
        float r = (float) splitRadius;
        WorldRenderKit.square(ctx.lines, ctx.pose, ctx.rx(splits.startX() - r), ctx.ry(splits.startY() + 0.02),
                ctx.rz(splits.startZ() - r), ctx.rx(splits.startX() + r), ctx.rz(splits.startZ() + r), 0xC060FF60);
        WorldRenderKit.square(ctx.lines, ctx.pose, ctx.rx(splits.finishX() - r), ctx.ry(splits.finishY() + 0.02),
                ctx.rz(splits.finishZ() - r), ctx.rx(splits.finishX() + r), ctx.rz(splits.finishZ() + r), 0xC0FF6060);
        if (splits.ghostLength() == 0) {
            return;
        }
        double gt = switch (splits.state()) {
            case RUNNING -> splits.elapsed() + ctx.partialTick;
            case FINISHED -> splits.ghostLength();
            default -> 0;
        };
        if (!splits.ghostAt(gt, ghostPos)) {
            return;
        }
        float x = ctx.rx(ghostPos[0]);
        float y = ctx.ry(ghostPos[1]);
        float z = ctx.rz(ghostPos[2]);
        WorldRenderKit.dashedBox(ctx.lines, ctx.pose, x - 0.3f, y, z - 0.3f, x + 0.3f, y + 1.8f, z + 0.3f, 0x8080C0FF);
    }

    // ---- HUD: split delta line ----------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (!isEnabled() || cleanScreen() || !splits.ready()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        ParkourSplits.State st = splits.state();
        if (st != ParkourSplits.State.RUNNING && !(st == ParkourSplits.State.FINISHED && everFinished)) {
            return;
        }
        if (cachedTick != tickCounter) {
            cachedTick = tickCounter;
            cached = buildLine(st, p);
        }
        int w = mc.font.width(cached);
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() / 2 + 24;
        g.drawString(mc.font, cached, x, y, 0xFFE0E0E0, true);
    }

    /** Rebuilt at most once per tick. */
    private String buildLine(ParkourSplits.State st, LocalPlayer p) {
        line.setLength(0);
        if (st == ParkourSplits.State.RUNNING) {
            line.append("Run ");
            appendSeconds(line, splits.elapsed());
            double d = splits.liveDelta(p.getX(), p.getY(), p.getZ());
            if (!Double.isNaN(d)) {
                line.append("  ");
                appendSigned(line, d);
            }
        } else {
            line.append("Finish ");
            appendSeconds(line, splits.lastRunTicks());
            line.append("  best ");
            appendSeconds(line, splits.bestTicks());
        }
        return line.toString();
    }

    static void appendSeconds(StringBuilder b, double ticks) {
        long cs = Math.round(ticks * 5.0); // 1 tick = 5 centiseconds
        b.append(cs / 100).append('.');
        long frac = cs % 100;
        if (frac < 10) {
            b.append('0');
        }
        b.append(frac).append('s');
    }

    static void appendSigned(StringBuilder b, double ticks) {
        b.append(ticks <= 0 ? '-' : '+');
        appendSeconds(b, Math.abs(ticks));
    }
}
