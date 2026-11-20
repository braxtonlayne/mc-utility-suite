package com.utilitysuite.combat.coach;

import com.utilitysuite.core.api.Services;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

/**
 * The coach's HUD cues around the crosshair, drawn as a GUI layer above the crosshair: a three-segment range bar
 * (too close / in range / too far), a swing-ready marker (gold when the swing would crit), "AXE" when a shield-break
 * swap is possible, "BACK" when the retreat rule fires, and a "!" on a ring pointing at incoming danger.
 * Draws nothing when the coach is disabled or idle, under Clean-Screen, with the GUI hidden or a screen open.
 * Allocation free (constant strings, no Components).
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

    private final FightCoach coach;

    public FightCoachHud(FightCoach coach) {
        this.coach = coach;
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker deltaTracker) {
        if (!coach.isEnabled()) {
            return;
        }
        CoachReadout r = coach.readout();
        if (!r.visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || Services.modState().isCleanScreen()) {
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
        if (r.danger != ThreatSource.NONE) {
            double a = Math.toRadians(r.dangerYawDeg);
            int dx = (int) Math.round(Math.sin(a) * DANGER_RING);
            int dy = (int) Math.round(-Math.cos(a) * DANGER_RING);
            g.drawString(font, DANGER, cx + dx - 1, cy + dy - 4, r.danger == ThreatSource.BLAST ? RED : ORANGE,
                    true);
        }
    }
}
