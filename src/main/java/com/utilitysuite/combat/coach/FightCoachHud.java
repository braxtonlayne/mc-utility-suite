package com.utilitysuite.combat.coach;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.IHudHost;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.IWorldMarks;
import com.utilitysuite.core.api.visual.MarkKind;
import com.utilitysuite.core.api.visual.TextRole;
import com.utilitysuite.ui.holo.FrameClock;
import java.util.function.BooleanSupplier;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;

/**
 * The coach's cues around the crosshair. Two halves:
 * <ul>
 *   <li>the GUI layer ({@link #render}) is the logic half: it gates, copies the attack recharge of the player into
 *       the readout, publishes incoming danger as {@code THREAT} world marks (the Shell draws a diamond on screen, an
 *       edge chevron with the distance off screen) and registers the HUD element once the Shell host exists. It
 *       draws nothing itself;</li>
 *   <li>the HUD element ({@link #draw}, slot CROSSHAIR) draws through the canvas in tokens: a cooldown arc around
 *       the crosshair that fills as the attack recharges and flashes when it is ready (WARN when a swing would
 *       crit), a three-segment range bar under it (too close / in range / too far), "AXE" when a shield-break swap
 *       is possible and "BACK" when the retreat rule fires. Without the tracker positions ({@link #useThreats}) the
 *       old "!" cue is drawn on a ring instead.</li>
 * </ul>
 * Draws and publishes nothing when the coach is disabled or idle, with the GUI hidden or a screen open (marks are
 * cleared then); Clean-Screen is handled by the HUD host. Allocation free (constant strings).
 */
public final class FightCoachHud implements LayeredDraw.Layer, HudElement {

    public static final String ID = "fight_coach";
    static final String PROJECTILE_MARK = "fight_coach.projectile";
    static final String BLAST_MARK = "fight_coach.blast";

    /** Radius of the cooldown arc around the crosshair and its line width. */
    static final float ARC_RADIUS = 9f;
    static final float ARC_WIDTH = 1.5f;
    /** How long the ready flash lasts. */
    static final long FLASH_MS = 260L;
    /** Alpha of the resting ready ring after the flash. */
    static final float READY_ALPHA = 0.55f;
    /** Range bar: three segments under the arc. */
    static final float SEG_W = 6f;
    static final float SEG_H = 2f;
    static final float SEG_GAP = 1f;
    static final float BAR_OFFSET = 14f;
    private static final float DANGER_RING = 22f;
    private static final String AXE = "AXE";
    private static final String BACK = "BACK";
    private static final String DANGER = "!";
    private static final int UNCLAIMED = -2;
    /** Half the edge of the box published for a projectile. */
    private static final double PROJECTILE_HALF = 0.25;

    private final CoachReadout readout;
    private final BooleanSupplier enabled;
    private final BooleanSupplier suppressed;
    private ThreatTracker tracker;
    private int projectileSlot = UNCLAIMED;
    private int blastSlot = UNCLAIMED;
    private boolean projectilePublished;
    private boolean blastPublished;
    private boolean hosted;
    private float lastCharge;
    private long readyAtMs = Long.MIN_VALUE / 2;

    public FightCoachHud(FightCoach coach) {
        this(coach::isEnabled, coach.readout(), FightCoachHud::screenOrGuiHidden);
    }

    /** Test seam: the readout and the gates are given instead of read from the game. */
    FightCoachHud(BooleanSupplier enabled, CoachReadout readout, BooleanSupplier suppressed) {
        this.enabled = enabled;
        this.readout = readout;
        this.suppressed = suppressed;
    }

    private static boolean screenOrGuiHidden() {
        Minecraft mc = Minecraft.getInstance();
        return mc.options.hideGui || mc.screen != null;
    }

    /** Gives the HUD the tracker's positions so danger is published as world marks (combat wiring, once). */
    public void useThreats(ThreatTracker threats) {
        this.tracker = threats;
    }

    // ---- logic half (GUI layer) -------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker deltaTracker) {
        if (!hosted) {
            IVisuals v = Services.visualsOrNull();
            if (v != null && v.hud() != IHudHost.NONE) {
                v.hud().register(this);
                hosted = true;
            }
        }
        if (!wantsDraw() || Services.modState().isCleanScreen()) {
            clearMarks();
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            readout.charge01 = clamp01(p.getAttackStrengthScale(0f));
        }
        ThreatTracker t = tracker;
        if (t != null) {
            publishThreats(t, readout, p);
        }
    }

    // ---- HUD element ------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HudSlot slot() {
        return HudSlot.CROSSHAIR;
    }

    @Override
    public boolean wantsDraw() {
        return enabled.getAsBoolean() && readout.visible && !suppressed.getAsBoolean();
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        CoachReadout r = readout;
        drawArc(c, r, x, y, alpha);

        // Range bar: three 6x2 segments under the arc; the active one carries the meaning.
        float bx = x - (SEG_W * 3f + SEG_GAP * 2f) * 0.5f;
        float by = y + BAR_OFFSET;
        for (int i = 0; i < 3; i++) {
            HoloToken t = HoloToken.LINE;
            if (i == r.range + 1) {
                t = i == 0 ? HoloToken.DANGER : (i == 1 ? HoloToken.ALLY : HoloToken.WARN);
            }
            c.plate(bx + i * (SEG_W + SEG_GAP), by, SEG_W, SEG_H, t, t == HoloToken.LINE ? alpha * 0.6f : alpha);
        }
        if (r.shieldBreak) {
            c.text(TextRole.MICRO, AXE, x + ARC_RADIUS + 4f, y - c.lineHeight(TextRole.MICRO) * 0.5f, HoloToken.WARN,
                    alpha);
        }
        if (r.retreat) {
            c.text(TextRole.MICRO, BACK, x - ARC_RADIUS - 4f - c.width(TextRole.MICRO, BACK),
                    y - c.lineHeight(TextRole.MICRO) * 0.5f, HoloToken.DANGER, alpha);
        }
        if (tracker == null && r.danger != ThreatSource.NONE) {
            double a = Math.toRadians(r.dangerYawDeg);
            float dx = (float) (Math.sin(a) * DANGER_RING);
            float dy = (float) (-Math.cos(a) * DANGER_RING);
            c.text(TextRole.MICRO, DANGER, x + dx - c.width(TextRole.MICRO, DANGER) * 0.5f,
                    y + dy - c.lineHeight(TextRole.MICRO) * 0.5f,
                    r.danger == ThreatSource.BLAST ? HoloToken.DANGER : HoloToken.WARN, alpha);
        }
    }

    /** The cooldown arc: a faint track, the filled part clockwise from the top, a full ring flash when it readies. */
    private void drawArc(IHoloCanvas c, CoachReadout r, float x, float y, float alpha) {
        float charge = clamp01(r.charge01);
        long now = FrameClock.nowMs();
        if (lastCharge < 1f && charge >= 1f) {
            readyAtMs = now;
        }
        lastCharge = charge;
        HoloToken ready = r.critReady ? HoloToken.WARN : HoloToken.ALLY;
        if (charge >= 1f) {
            long since = now - readyAtMs;
            if (since < FLASH_MS) {
                float k = 1f - (float) since / FLASH_MS;
                c.arc(x, y, ARC_RADIUS + 2f * k, 0f, 360f, ARC_WIDTH + k, ready, alpha);
            } else {
                c.arc(x, y, ARC_RADIUS, 0f, 360f, ARC_WIDTH, ready, alpha * READY_ALPHA);
            }
            return;
        }
        c.arc(x, y, ARC_RADIUS, 0f, 360f, 1f, HoloToken.LINE_FAINT, alpha);
        if (charge > 0.01f) {
            c.arc(x, y, ARC_RADIUS, 0f, 360f * charge, ARC_WIDTH, HoloToken.ACCENT, alpha);
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    // ---- marks ------------------------------------------------------------------------------------------------------

    /** Publishes the projectile and blast the tracker evaluated this tick (only while the coach shows danger). */
    private void publishThreats(ThreatTracker t, CoachReadout r, LocalPlayer p) {
        if (p == null || r.danger == ThreatSource.NONE) {
            clearMarks();
            return;
        }
        IWorldMarks marks = Services.visuals().marks();
        if (t.projTicks != Integer.MAX_VALUE) {
            if (projectileSlot == UNCLAIMED) {
                projectileSlot = marks.claim(PROJECTILE_MARK);
            }
            if (projectileSlot >= 0) {
                double x = p.getX() + t.projRx;
                double y = p.getY() + p.getBbHeight() * 0.5 + t.projRy;
                double z = p.getZ() + t.projRz;
                int flags = t.projDamage >= p.getHealth() + p.getAbsorptionAmount() ? IWorldMarks.FLAG_LETHAL : 0;
                marks.setBox(projectileSlot, MarkKind.THREAT, x - PROJECTILE_HALF, y - PROJECTILE_HALF,
                        z - PROJECTILE_HALF, x + PROJECTILE_HALF, y + PROJECTILE_HALF, z + PROJECTILE_HALF, null,
                        flags);
                projectilePublished = true;
            }
        } else if (projectilePublished) {
            marks.clear(projectileSlot);
            projectilePublished = false;
        }
        if (t.blastTicks != Integer.MAX_VALUE) {
            if (blastSlot == UNCLAIMED) {
                blastSlot = marks.claim(BLAST_MARK);
            }
            if (blastSlot >= 0) {
                double x = p.getX() + t.blastRx;
                double z = p.getZ() + t.blastRz;
                marks.setBox(blastSlot, MarkKind.THREAT, x - 0.5, p.getY(), z - 0.5, x + 0.5, p.getY() + 1.0,
                        z + 0.5, null, IWorldMarks.FLAG_LETHAL);
                blastPublished = true;
            }
        } else if (blastPublished) {
            marks.clear(blastSlot);
            blastPublished = false;
        }
    }

    private void clearMarks() {
        if (!projectilePublished && !blastPublished) {
            return;
        }
        IWorldMarks marks = Services.visuals().marks();
        if (projectilePublished) {
            marks.clear(projectileSlot);
            projectilePublished = false;
        }
        if (blastPublished) {
            marks.clear(blastSlot);
            blastPublished = false;
        }
    }
}
