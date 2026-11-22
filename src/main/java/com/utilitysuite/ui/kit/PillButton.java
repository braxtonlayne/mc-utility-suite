package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/** Rectangular framed button: primary (accent outline), secondary, ghost, danger (solid / soft) and success. */
public final class PillButton extends UiNode {

    public enum Style { PRIMARY, SECONDARY, GHOST, OUTLINE, DANGER, DANGER_SOFT, SUCCESS }

    public static final int H = 16;

    private String label;
    private String shown;
    private int shownWidth;
    private Style style;
    private final Consumer<PillButton> onPress;
    private boolean pill;
    private boolean selected;
    private boolean enabled = true;
    private int textColorOverride;

    public PillButton(Font font, String label, int w, Style style, Consumer<PillButton> onPress) {
        super(w, H);
        this.style = style;
        this.onPress = onPress;
        setLabel(font, label);
    }

    public PillButton setLabel(Font font, String text) {
        label = text == null ? "" : text;
        shown = UiRenderUtils.ellipsize(font, label, Math.max(0, w - 10));
        shownWidth = UiRenderUtils.width(font, shown);
        return this;
    }

    public String label() {
        return label;
    }

    public PillButton pill(boolean v) {
        pill = v;
        return this;
    }

    public PillButton selected(boolean v) {
        selected = v;
        return this;
    }

    public PillButton enabled(boolean v) {
        enabled = v;
        return this;
    }

    public PillButton style(Style s) {
        style = s;
        return this;
    }

    /** Overrides the label color (e.g. a pinned hotbar slot in gold); 0 = style default. */
    public PillButton textColor(int argb) {
        textColorOverride = argb;
        return this;
    }

    @Override
    public boolean interactive() {
        return enabled;
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = enabled && inside(mouseX, mouseY, sx, sy, w, h);
        float hv = hoverAmount(hover);
        float press = pressAmount();
        int fill = 0;
        int edge = 0;
        int fg;
        int accent = HoloTheme.argb(HoloToken.ACCENT);
        int text = HoloTheme.argb(HoloToken.TEXT);
        int line = HoloTheme.argb(HoloToken.LINE);
        int lineStrong = HoloTheme.argb(HoloToken.LINE_STRONG);
        int raised = HoloTheme.argb(HoloToken.GLASS_RAISED);
        switch (style) {
            case PRIMARY -> {
                fill = HoloTheme.mix(HoloTheme.argb(HoloToken.ACCENT_GLOW), HoloTheme.fade(accent, 0.30f), hv);
                edge = HoloTheme.mix(accent, text, hv * 0.25f);
                fg = HoloTheme.mix(accent, text, hv * 0.35f);
            }
            case OUTLINE -> {
                fill = HoloTheme.fade(raised, hv);
                edge = HoloTheme.mix(line, lineStrong, hv);
                fg = HoloTheme.mix(accent, text, hv * 0.35f);
            }
            case GHOST -> {
                fill = HoloTheme.fade(raised, hv);
                fg = HoloTheme.mix(HoloTheme.argb(HoloToken.TEXT_SECONDARY), text, hv);
            }
            case DANGER -> {
                int danger = HoloTheme.argb(HoloToken.DANGER);
                fill = HoloTheme.mix(danger, text, hv * 0.22f);
                edge = HoloTheme.mix(danger, text, 0.35f);
                fg = text;
            }
            case DANGER_SOFT -> {
                int danger = HoloTheme.argb(HoloToken.DANGER);
                fill = HoloTheme.mix(HoloTheme.fade(danger, 0.12f), HoloTheme.fade(danger, 0.28f), hv);
                edge = HoloTheme.mix(HoloTheme.fade(danger, 0.65f), danger, hv);
                fg = UiTheme.DANGER_TEXT;
            }
            case SUCCESS -> {
                int sync = HoloTheme.argb(HoloToken.SYNC);
                fill = HoloTheme.mix(HoloTheme.fade(sync, 0.12f), HoloTheme.fade(sync, 0.28f), hv);
                edge = HoloTheme.mix(HoloTheme.fade(sync, 0.65f), sync, hv);
                fg = HoloTheme.mix(sync, text, hv * 0.3f);
            }
            default -> {
                if (selected) {
                    fill = HoloTheme.mix(HoloTheme.argb(HoloToken.ACCENT_GLOW), HoloTheme.fade(accent, 0.30f), hv);
                    edge = HoloTheme.mix(lineStrong, text, hv * 0.2f);
                    fg = accent;
                } else {
                    fill = HoloTheme.mix(raised, HoloTheme.mix(raised, accent, 0.10f), hv);
                    edge = HoloTheme.mix(HoloTheme.argb(HoloToken.LINE_FAINT), line, hv);
                    fg = HoloTheme.mix(HoloTheme.argb(HoloToken.TEXT_SECONDARY), text, hv);
                }
            }
        }
        if (press > 0.01f) {
            fill = HoloTheme.mix(fill, HoloTheme.fade(text, 0.30f), press);
            edge = HoloTheme.mix(edge, text, press);
        }
        if (!enabled) {
            fill = HoloTheme.fade(fill, 0.5f);
            edge = HoloTheme.fade(edge, 0.5f);
            fg = HoloTheme.argb(HoloToken.TEXT_MUTED);
        }
        if (textColorOverride != 0 && enabled) {
            fg = textColorOverride;
        }
        if ((fill >>> 24) != 0) {
            g.fill(sx, sy, sx + w, sy + h, fill);
        }
        if ((edge >>> 24) != 0) {
            HoloFrames.edge(g, sx, sy, w, h, edge);
        }
        if (focused) {
            HoloFrames.brackets(g, sx - 1, sy - 1, w + 2, h + 2, 3, lineStrong);
        }
        UiRenderUtils.text(g, font, shown, sx + (w - shownWidth) / 2, sy + (h - 8) / 2, fg);
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (!enabled || button != 0) {
            return false;
        }
        flashPress();
        onPress.accept(this);
        return true;
    }
}
