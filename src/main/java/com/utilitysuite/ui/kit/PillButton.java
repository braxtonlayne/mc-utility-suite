package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/** Flat button: primary (accent), secondary, ghost, danger (solid / soft) and success, optionally fully rounded. */
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
        shownWidth = font.width(shown);
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
        int r = pill ? h / 2 : UiTheme.RADIUS_SMALL;
        int bg;
        int border = 0;
        int fg;
        switch (style) {
            case PRIMARY -> {
                bg = hover ? UiTheme.ACCENT_HOVER : UiTheme.ACCENT;
                fg = UiTheme.ON_ACCENT;
            }
            case OUTLINE -> {
                bg = hover ? UiTheme.CONTROL : 0;
                border = UiTheme.ACCENT_BORDER;
                fg = hover ? UiTheme.TEXT : UiTheme.ACCENT;
            }
            case GHOST -> {
                bg = hover ? UiTheme.CONTROL : 0;
                fg = hover ? UiTheme.TEXT : UiTheme.TEXT_SECONDARY;
            }
            case DANGER -> {
                bg = hover ? 0xFFF26B6B : UiTheme.DANGER;
                fg = 0xFFFFFFFF;
            }
            case DANGER_SOFT -> {
                bg = hover ? 0x40EF4444 : UiTheme.DANGER_TINT;
                border = UiTheme.DANGER_BORDER;
                fg = UiTheme.DANGER_TEXT;
            }
            case SUCCESS -> {
                bg = hover ? 0xFF34D399 : UiTheme.RUNNING;
                fg = 0xFF04140D;
            }
            default -> {
                if (selected) {
                    bg = hover ? 0x3838BDF8 : UiTheme.ACCENT_TINT;
                    border = UiTheme.ACCENT_BORDER;
                    fg = UiTheme.ACCENT;
                } else {
                    bg = hover ? UiTheme.CONTROL_HOVER : UiTheme.CONTROL;
                    border = hover ? UiTheme.FIELD_BORDER_HOVER : UiTheme.CONTROL_BORDER;
                    fg = hover ? UiTheme.TEXT : UiTheme.TEXT_SECONDARY;
                }
            }
        }
        if (!enabled) {
            bg = UiTheme.fade(bg, 0.5f);
            border = UiTheme.fade(border, 0.5f);
            fg = UiTheme.TEXT_MUTED;
        }
        if (textColorOverride != 0 && enabled) {
            fg = textColorOverride;
        }
        UiRenderUtils.roundedRect(g, sx, sy, w, h, r, bg);
        if (border != 0) {
            UiRenderUtils.roundedOutline(g, sx, sy, w, h, r, border);
        }
        if (focused) {
            UiRenderUtils.roundedOutline(g, sx - 1, sy - 1, w + 2, h + 2, r + 1, UiTheme.ACCENT_BORDER);
        }
        UiRenderUtils.text(g, font, shown, sx + (w - shownWidth) / 2, sy + (h - 8) / 2, fg);
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (!enabled || button != 0) {
            return false;
        }
        onPress.accept(this);
        return true;
    }
}
