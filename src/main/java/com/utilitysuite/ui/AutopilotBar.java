package com.utilitysuite.ui;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.autopilot.Autopilot;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.holo.Motion;
import com.utilitysuite.ui.kit.UiNode;
import com.utilitysuite.ui.kit.UiRenderUtils;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The autopilot's own toggle, pinned across the top of the menu under the title bar, above the category panels. A
 * click on the bar turns the autopilot on or off; the "Settings" end opens its page (which reflexes it may use, how far
 * it may go). After the ON/OFF badge a row of reflex chips shows which reflexes may act (lit) and which may not (dim):
 * the four original reflexes always, the newer ones only when the width allows. Chip widths and lit states are cached
 * in {@link #refresh} (every few ticks), never built per frame.
 * <p>
 * Drawn as the STEM strip (design stem-ui 8.4): quiet while off (muted label, faint chips); on, a 2 px accent bar at
 * the left, the label and lit chips in accent, and the chip of a reflex that acted in the last 10 s in SYNC with a
 * diamond. Turning it on unfolds the strip over 300 ms with a one-shot sweep across the chips.
 */
final class AutopilotBar extends UiNode {

    static final int H = 22;
    private static final String LABEL = "AUTOPILOT";
    private static final int SETTINGS_W = 74;
    private static final int CHIP_H = 12;
    private static final int CHIP_GAP = 4;
    /** When each chip's reflex was last seen acting (indexed like ORDER); survives bar rebuilds and reopening. */
    private static final long[] ACTED_AT = new long[ReflexChips.ORDER.length];
    private static final long UNFOLD_KEY = AnimStore.key("menu.autopilot.unfold", 0);
    private static final float UNFOLD_S = 0.30f;

    private final Runnable onExpand;
    private final boolean expanded;
    private boolean on;
    private String keyHint = "";
    private final String settingsLabel;
    private final int[] chipW = new int[ReflexChips.ORDER.length];
    private final boolean[] chipLit = new boolean[ReflexChips.ORDER.length];
    private int chipCount;
    private int chipX;
    private final int labelW;
    private final int onW;
    private final int offW;
    private final int settingsW;
    private int keyHintW;

    AutopilotBar(Font font, int w, boolean expanded, Runnable onExpand) {
        super(w, H);
        this.expanded = expanded;
        this.onExpand = onExpand;
        this.settingsLabel = expanded ? "Settings ▴" : "Settings ▾";
        this.labelW = com.utilitysuite.ui.kit.UiRenderUtils.width(font, LABEL);
        this.onW = com.utilitysuite.ui.kit.UiRenderUtils.width(font, "ON");
        this.offW = com.utilitysuite.ui.kit.UiRenderUtils.width(font, "OFF");
        this.settingsW = com.utilitysuite.ui.kit.UiRenderUtils.width(font, settingsLabel);
        tooltip("Autopilot: lets the reflexes you allow act for you (a lethal fall is clutched, a run gets parkour"
                + " timing), even when their own module is off. Lit chips are reflexes it may use. Your keys and mouse"
                + " always win. Click to turn it on or off.");
        for (int i = 0; i < chipW.length; i++) {
            chipW[i] = com.utilitysuite.ui.kit.UiRenderUtils.width(font, ReflexChips.CHIP_LABEL[ReflexChips.ORDER[i].ordinal()]) + 8;
        }
        chipX = 10 + com.utilitysuite.ui.kit.UiRenderUtils.width(font, LABEL) + 8 + com.utilitysuite.ui.kit.UiRenderUtils.width(font, "OFF") + 10 + 10; // fixed column after the badge
        refresh(font);
    }

    @Override
    public void refresh(Font font) {
        Autopilot ap = CoreBootstrap.autopilot();
        on = ap != null && ap.isEnabled(); // the toggle, not the hand-over key
        KeyMapping key = KeybindManager.mapping(KeybindManager.KeyId.TOGGLE_AUTOPILOT);
        keyHint = key == null || key.isUnbound() ? "" : "[" + key.getTranslatedKeyMessage().getString() + "]";
        keyHintW = keyHint.isEmpty() ? 0 : com.utilitysuite.ui.kit.UiRenderUtils.width(font, keyHint);
        int room = w - chipX - SETTINGS_W - 8 - (keyHint.isEmpty() ? 0 : keyHintW + 10);
        IAutopilot svc = Services.autopilotOrNull();
        if (svc == null) {
            svc = ap;
        }
        chipCount = svc == null ? 0 : ReflexChips.visibleCount(Math.max(0, room), chipW, CHIP_GAP);
        ReflexChips.fillLit(svc, chipLit);
        // the reflex acting right now, sampled at the refresh cadence (every few ticks), never per frame
        ReflexChips.noteActive(svc == null || !svc.isOn() ? null : svc.activeReflex(), Util.getMillis(), ACTED_AT);
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (button != 0) {
            return false;
        }
        if (mouseX >= sx + w - SETTINGS_W) {
            onExpand.run();
            return true;
        }
        CoreBootstrap.setAutopilot(!on);
        on = !on;
        return true;
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        boolean hoverSettings = hover && mouseX >= sx + w - SETTINGS_W;
        long now = Util.getMillis();
        // 300 ms unfold when the autopilot turns on (folds back when it turns off); a new key starts at the end state
        float p = AnimStore.easeOut(AnimStore.SHARED.progress(UNFOLD_KEY, on, UNFOLD_S));
        boolean slides = HoloTheme.motion == Motion.FULL;
        float geo = slides ? p : (on ? 1f : 0f); // REDUCED keeps the fade, drops the unfold geometry
        if (hover && !hoverSettings) {
            g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.GLASS_RAISED, 0.55f));
        }
        if (p > 0f) {
            int ww = (int) ((w - SETTINGS_W) * geo);
            g.fill(sx, sy, sx + ww, sy + h, HoloTheme.argb(HoloToken.ACCENT_GLOW, 0.45f * p));
            int bh = Math.max(1, (int) (h * geo));
            int by = sy + (h - bh) / 2;
            g.fill(sx, by, sx + HoloTheme.BAR, by + bh, HoloTheme.argb(HoloToken.ACCENT, p));
        }
        if (p < 1f) {
            g.fill(sx, sy + 5, sx + HoloTheme.BAR, sy + h - 5, HoloTheme.argb(HoloToken.LINE_FAINT, 1f - p));
        }
        int ty = sy + (h - 8) / 2;
        int x = sx + 10;
        UiRenderUtils.text(g, font, LABEL, x, ty, on ? HoloTheme.argb(HoloToken.ACCENT)
                : HoloTheme.argb(hover && !hoverSettings ? HoloToken.TEXT_SECONDARY : HoloToken.TEXT_MUTED));
        x += labelW + 8;
        // state readout in a hairline frame (no solid fill)
        String state = on ? "ON" : "OFF";
        int bw = (on ? onW : offW) + 10;
        int by = sy + (h - 12) / 2;
        if (on) {
            g.fill(x, by, x + bw, by + 12, HoloTheme.argb(HoloToken.ACCENT_GLOW, 0.6f));
        }
        HoloFrames.edge(g, x, by, bw, 12, HoloTheme.argb(on ? HoloToken.LINE : HoloToken.LINE_FAINT));
        UiRenderUtils.text(g, font, state, x + 5, by + 2, HoloTheme.argb(on ? HoloToken.ACCENT : HoloToken.TEXT_FAINT));
        // reflex chips: lit = may act (accent), unlit = faint, acted in the last 10 s = SYNC with a diamond
        x = sx + chipX;
        int cy = sy + (h - CHIP_H) / 2;
        boolean reveal = slides && on && p > 0f && p < 1f;
        if (reveal) { // one-shot sweep: the chips appear left to right behind the unfold
            UiRenderUtils.clip(g, sx + chipX, sy, Math.max(1, (int) ((w - chipX - SETTINGS_W) * p)), h);
        }
        for (int i = 0; i < chipCount; i++) {
            boolean lit = chipLit[i];
            boolean acting = on && ReflexChips.actedRecently(ACTED_AT[i], now);
            int cw = chipW[i];
            HoloToken tone = acting ? HoloToken.SYNC : lit ? HoloToken.ACCENT : HoloToken.TEXT_FAINT;
            if (acting) {
                int sync = HoloTheme.argb(HoloToken.SYNC);
                g.fill(x, cy, x + cw, cy + CHIP_H, HoloTheme.argb(HoloToken.SYNC, 0.14f));
                HoloFrames.edge(g, x, cy, cw, CHIP_H, HoloTheme.argb(HoloToken.SYNC, 0.8f));
                int dx = x + cw / 2; // small diamond sitting on the chip's top edge
                g.fill(dx, cy - 2, dx + 1, cy - 1, sync);
                g.fill(dx - 1, cy - 1, dx + 2, cy, sync);
                g.fill(dx - 2, cy, dx + 3, cy + 1, sync);
            } else {
                HoloFrames.edge(g, x, cy, cw, CHIP_H, HoloTheme.argb(lit ? HoloToken.LINE : HoloToken.LINE_FAINT));
            }
            UiRenderUtils.text(g, font, ReflexChips.CHIP_LABEL[ReflexChips.ORDER[i].ordinal()], x + 4, cy + 2,
                    HoloTheme.argb(tone));
            x += cw + CHIP_GAP;
        }
        if (reveal) {
            UiRenderUtils.endClip(g);
        }
        int rx = sx + w - SETTINGS_W;
        if (!keyHint.isEmpty()) {
            UiRenderUtils.text(g, font, keyHint, rx - 8 - keyHintW, ty,
                    HoloTheme.argb(HoloToken.TEXT_MUTED));
        }
        HoloFrames.vLine(g, rx, sy + 4, sy + h - 4, HoloTheme.argb(HoloToken.LINE_FAINT));
        if (hoverSettings || expanded) {
            g.fill(rx + 1, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.GLASS_RAISED, 0.8f));
        }
        if (expanded) {
            g.fill(rx + 1, sy + h - HoloTheme.BAR, sx + w, sy + h, HoloTheme.argb(HoloToken.ACCENT));
        }
        UiRenderUtils.text(g, font, settingsLabel, rx + (SETTINGS_W - settingsW) / 2, ty,
                HoloTheme.argb(expanded ? HoloToken.ACCENT : hoverSettings ? HoloToken.TEXT : HoloToken.TEXT_SECONDARY));
    }
}
