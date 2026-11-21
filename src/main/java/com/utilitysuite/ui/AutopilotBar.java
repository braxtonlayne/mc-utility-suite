package com.utilitysuite.ui;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.autopilot.Autopilot;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.kit.UiNode;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The autopilot's own toggle, pinned across the top of the menu under the title bar, above the category panels. A
 * click on the bar turns the autopilot on or off; the "Settings" end opens its page (which reflexes it may use, how far
 * it may go). Text is cached in {@link #refresh} (every few ticks), never built per frame.
 */
final class AutopilotBar extends UiNode {

    static final int H = 22;
    private static final String LABEL = "AUTOPILOT";
    private static final int SETTINGS_W = 74;

    private final Runnable onExpand;
    private final boolean expanded;
    private boolean on;
    private String summary = "";
    private String keyHint = "";
    private final String settingsLabel;

    AutopilotBar(Font font, int w, boolean expanded, Runnable onExpand) {
        super(w, H);
        this.expanded = expanded;
        this.onExpand = onExpand;
        this.settingsLabel = expanded ? "Settings ▴" : "Settings ▾";
        tooltip("Autopilot: lets the reflexes you allow act for you (a lethal fall is clutched, a run gets parkour"
                + " timing), even when their own module is off. Your keys and mouse always win. Click to turn it on"
                + " or off.");
        refresh(font);
    }

    @Override
    public void refresh(Font font) {
        Autopilot ap = CoreBootstrap.autopilot();
        on = ap != null && ap.isOn();
        KeyMapping key = KeybindManager.mapping(KeybindManager.KeyId.TOGGLE_AUTOPILOT);
        keyHint = key == null || key.isUnbound() ? "" : "[" + key.getTranslatedKeyMessage().getString() + "]";
        int room = w - 24 - font.width(LABEL) - 40 - SETTINGS_W - (keyHint.isEmpty() ? 0 : font.width(keyHint) + 10);
        int fit = Math.max(20, room);
        if (ap == null) {
            summary = "";
        } else if (!on) {
            summary = UiRenderUtils.firstThatFits(font, fit, ap.summary(), "Off: click to let reflexes act for you",
                    "Off: click to turn on", "Off");
        } else {
            int n = 0;
            for (com.utilitysuite.core.api.IAutopilot.Reflex r : com.utilitysuite.core.api.IAutopilot.Reflex.values()) {
                n += ap.allows(r) ? 1 : 0;
            }
            summary = UiRenderUtils.firstThatFits(font, fit, ap.summary(),
                    "Watching " + n + (n == 1 ? " reflex" : " reflexes"), n + " on");
        }
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
        g.fill(sx, sy, sx + w, sy + h, on ? UiTheme.RUNNING_TINT : hover && !hoverSettings ? UiTheme.ROW_HOVER
                : UiTheme.INSET);
        g.fill(sx, sy, sx + 3, sy + h, on ? UiTheme.RUNNING : UiTheme.TRACK_OFF);
        int ty = sy + (h - 8) / 2;
        int x = sx + 10;
        UiRenderUtils.text(g, font, LABEL, x, ty, on ? UiTheme.TEXT : UiTheme.TEXT_SECONDARY);
        x += font.width(LABEL) + 8;
        // classic ON/OFF state badge
        String state = on ? "ON" : "OFF";
        int bw = font.width(state) + 10;
        UiRenderUtils.panel(g, x, sy + (h - 12) / 2, bw, 12, 3, on ? UiTheme.RUNNING : UiTheme.CONTROL,
                on ? UiTheme.RUNNING : UiTheme.CONTROL_BORDER);
        UiRenderUtils.text(g, font, state, x + 5, sy + (h - 12) / 2 + 2, on ? UiTheme.ON_ACCENT : UiTheme.TEXT_MUTED);
        x += bw + 10;
        UiRenderUtils.text(g, font, summary, x, ty, on ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED);
        int rx = sx + w - SETTINGS_W;
        if (!keyHint.isEmpty()) {
            UiRenderUtils.text(g, font, keyHint, rx - 8 - font.width(keyHint), ty, UiTheme.TEXT_MUTED);
        }
        UiRenderUtils.vLine(g, rx, sy + 4, sy + h - 4, UiTheme.BORDER);
        if (hoverSettings || expanded) {
            g.fill(rx + 1, sy, sx + w, sy + h, UiTheme.ROW_HOVER);
        }
        UiRenderUtils.text(g, font, settingsLabel, rx + (SETTINGS_W - font.width(settingsLabel)) / 2, ty,
                expanded ? UiTheme.ACCENT : UiTheme.TEXT_DIM);
    }
}
