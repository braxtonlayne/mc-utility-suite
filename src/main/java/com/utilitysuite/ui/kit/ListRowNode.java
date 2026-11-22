package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * Background of one list row (queued jobs, waypoints, allies, rules, files): hover highlight, an accent bar when
 * selected, a hairline separator under it, and optionally a label and a click action of its own (selectable rows).
 * Row controls are separate nodes drawn on top.
 */
public final class ListRowNode extends UiNode {

    private final Consumer<ListRowNode> onPress;
    private String label = "";
    private int labelColor = UiTheme.TEXT_SECONDARY;
    private boolean selected;
    private boolean separator = true;

    public ListRowNode(int w, int h, Consumer<ListRowNode> onPress) {
        super(w, h);
        this.onPress = onPress;
    }

    public ListRowNode label(Font font, String text, int maxW, int color) {
        label = UiRenderUtils.ellipsize(font, text, maxW);
        labelColor = color;
        return this;
    }

    public ListRowNode selected(boolean v) {
        selected = v;
        return this;
    }

    public ListRowNode separator(boolean v) {
        separator = v;
        return this;
    }

    @Override
    public boolean interactive() {
        return onPress != null;
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        float hv = hoverAmount(hover);
        int accent = HoloTheme.argb(HoloToken.ACCENT);
        if (selected) {
            g.fill(sx, sy, sx + w, sy + h, HoloTheme.mix(HoloTheme.argb(HoloToken.ACCENT_GLOW),
                    HoloTheme.fade(accent, 0.30f), hv));
            HoloFrames.sideBar(g, sx, sy + 2, h - 4, HoloToken.ACCENT, 1f);
        } else {
            if (hv > 0.01f) {
                g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.GLASS_RAISED, hv));
            }
            if (separator && hv < 0.5f) {
                UiRenderUtils.hLine(g, sx + 6, sx + w - 6, sy + h, HoloTheme.argb(HoloToken.LINE_FAINT));
            }
        }
        if (!label.isEmpty()) {
            int idle = onPress != null ? HoloTheme.mix(labelColor, UiTheme.TEXT, hv) : labelColor;
            UiRenderUtils.text(g, font, label, sx + 8, sy + (h - 8) / 2, selected ? accent : idle);
        }
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (onPress == null || button != 0) {
            return false;
        }
        onPress.accept(this);
        return true;
    }
}
