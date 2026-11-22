package com.utilitysuite.combat.coach;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.IWorldMarks;
import com.utilitysuite.core.api.visual.MarkKind;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;

/**
 * The coach's HUD cues around the crosshair, drawn as a GUI layer above the crosshair: a three-segment range bar
 * (too close / in range / too far), a swing-ready marker (gold when the swing would crit), "AXE" when a shield-break
 * swap is possible and "BACK" when the retreat rule fires.
 * <p>
 * Incoming danger is published as {@code THREAT} world marks (the Shell draws a diamond on screen, an edge chevron
 * with the distance off screen): the most imminent projectile in {@code WARN} ({@code FLAG_LETHAL} when it would
 * kill) and a lit creeper or primed TNT as {@code FLAG_LETHAL}. That needs the tracker's positions
 * ({@link #useThreats}); without it the old "!" arrow on a ring is drawn instead.
 * Draws and publishes nothing when the coach is disabled or idle, under Clean-Screen, with the GUI hidden or a screen
 * open (marks are cleared then). Allocation free (constant strings, no Components).
 */
public final class FightCoachHud implements LayeredDraw.Layer {

    private static final int DIM = 0x50FFFFFF;
    private static final int RED = 0xFFE05050;
    private static final int GREEN = 0xFF50E070;
    private static final int AMBER = 0xFFE0B040;
    private static final int GOLD = 0xFFFFD700;
    private static final int ORANGE = 0xFFFF9030;
    private static final String AXE = "AXE";
    private static final String BACK = "BACK";
    private static final String DANGER = "!";
    private static final int DANGER_RING = 22;
    static final String PROJECTILE_MARK = "fight_coach.projectile";
    static final String BLAST_MARK = "fight_coach.blast";
    private static final int UNCLAIMED = -2;
    /** Half the edge of the box published for a projectile. */
    private static final double PROJECTILE_HALF = 0.25;

    private final FightCoach coach;
    private ThreatTracker tracker;
    private int projectileSlot = UNCLAIMED;
    private int blastSlot = UNCLAIMED;
    private boolean projectilePublished;
    private boolean blastPublished;

    public FightCoachHud(FightCoach coach) {
        this.coach = coach;
    }

    /** Gives the HUD the tracker's positions so danger is published as world marks (combat wiring, once). */
    public void useThreats(ThreatTracker threats) {
        this.tracker = threats;
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker deltaTracker) {
        if (!coach.isEnabled()) {
            clearMarks();
            return;
        }
        CoachReadout r = coach.readout();
        if (!r.visible) {
            clearMarks();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || Services.modState().isCleanScreen()) {
            clearMarks();
            return;
        }
        Font font = mc.font;
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;

        // Range bar: three 6x2 segments under the crosshair; the active one is coloured.
        int y = cy + 9;
        int x0 = cx - 10;
        for (int i = 0; i < 3; i++) {
            int color = DIM;
            if (i == r.range + 1) {
                color = i == 0 ? RED : (i == 1 ? GREEN : AMBER);
            }
            g.fill(x0 + i * 7, y, x0 + i * 7 + 6, y + 2, color);
        }

        // Swing-ready marker right of the crosshair.
        if (r.swingReady) {
            g.fill(cx + 7, cy - 1, cx + 10, cy + 2, r.critReady ? GOLD : GREEN);
        }
        if (r.shieldBreak) {
            g.drawString(font, AXE, cx + 13, cy - 4, AMBER, true);
        }
        if (r.retreat) {
            g.drawString(font, BACK, cx - 12 - font.width(BACK), cy - 4, RED, true);
        }
        ThreatTracker t = tracker;
        if (t != null) {
            publishThreats(t, r, mc.player);
        } else if (r.danger != ThreatSource.NONE) {
            double a = Math.toRadians(r.dangerYawDeg);
            int dx = (int) Math.round(Math.sin(a) * DANGER_RING);
            int dy = (int) Math.round(-Math.cos(a) * DANGER_RING);
            g.drawString(font, DANGER, cx + dx - 1, cy + dy - 4, r.danger == ThreatSource.BLAST ? RED : ORANGE,
                    true);
        }
    }

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
