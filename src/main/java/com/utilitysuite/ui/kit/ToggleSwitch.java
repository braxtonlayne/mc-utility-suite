package com.utilitysuite.ui.kit;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.Consumer;

/**
 * Bracketed rail switch. The layout box stays 28x14 (hit box and callers unchanged); the 22x10 rail is centred in it.
 * OFF: a {@code LINE} outline with a dim bar at the left. ON: {@code ACCENT_DEEP} fill with a bright bar at the right
 * ({@code WARN} for a less-legit toggle, see {@link #warn}). The bar slides over {@link UiTheme#TOGGLE_MS} through
 * {@link AnimStore#progress} under a stable key, so a switch rebuilt right after a click keeps sliding.
 */
public final class ToggleSwitch extends UiNode {

    public static final int W = 28;
    public static final int H = 14;
    /** Visible rail. */
    public static final int RAIL_W = 22;
    public static final int RAIL_H = 10;
    private static final float DURATION_S = UiTheme.TOGGLE_MS / 1000f;
    private static final int BAR_W = 8;
    private static final int BAR_H = 4;

    private boolean value;
    private boolean warn;
    private final Consumer<Boolean> onChange;

    public ToggleSwitch(boolean value, Consumer<Boolean> onChange) {
        super(W, H);
        this.value = value;
        this.onChange = onChange;
    }

    /** Continues the animation of a previous switch with the same key (menu rebuilds). */
    public ToggleSwitch animKey(String key) {
        if (key != null) {
            animId(WidgetKeys.named(key));
        }
        return this;
    }

    /** Same, with an already hashed stable key (see {@link WidgetKeys}). */
    public ToggleSwitch animKey(long key) {
        animId(key);
        return this;
    }

    /** Marks this switch as a less-legit option: its ON bar is drawn in the warning colour. */
    public ToggleSwitch warn(boolean v) {
        warn = v;
        return this;
    }

    public boolean value() {
        return value;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public boolean focusable() {
        return true;
    }

    /** Bar position 0 (off) .. 1 (on): linear progress through the shared store, eased out. Advances the store. */
    public float slide() {
        float p = AnimStore.SHARED.progress(WidgetKeys.channel(key(), CH_SLIDE), value, DURATION_S);
        return AnimStore.easeOut(p);
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        float hv = hoverAmount(hover);
        float press = pressAmount();
        float p = slide();
        int rx = sx + (W - RAIL_W) / 2;
        int ry = sy + (H - RAIL_H) / 2;
        int lineOff = HoloTheme.argb(HoloToken.LINE);
        int lineOn = HoloTheme.argb(warn ? HoloToken.WARN : HoloToken.LINE_STRONG);
        int edge = HoloTheme.mix(lineOff, lineOn, p);
        edge = HoloTheme.mix(edge, HoloTheme.argb(HoloToken.LINE_STRONG), hv * 0.6f);
        edge = HoloTheme.mix(edge, HoloTheme.argb(HoloToken.TEXT), press * 0.8f);
        int fill = HoloTheme.mix(HoloTheme.argb(HoloToken.FIELD), HoloTheme.argb(HoloToken.ACCENT_DEEP), p);
        g.fill(rx, ry, rx + RAIL_W, ry + RAIL_H, fill);
        HoloFrames.edge(g, rx, ry, RAIL_W, RAIL_H, edge);
        HoloFrames.brackets(g, rx - 1, ry - 1, RAIL_W + 2, RAIL_H + 2, 3,
                HoloTheme.fade(edge, 0.55f + 0.45f * p));
        int barOn = HoloTheme.argb(warn ? HoloToken.WARN : HoloToken.ACCENT);
        int bar = HoloTheme.mix(HoloTheme.argb(HoloToken.TEXT_MUTED), barOn, p);
        int bx = rx + 2 + Math.round(p * (RAIL_W - 4 - BAR_W));
        int by = ry + (RAIL_H - BAR_H) / 2;
        g.fill(bx, by, bx + BAR_W, by + BAR_H, bar);
        if (focused) {
            HoloFrames.edge(g, sx, sy, W, H, HoloTheme.argb(HoloToken.LINE_STRONG));
        }
    }

    public void set(boolean v, boolean notify) {
        if (v == value) {
            return;
        }
        value = v;
        flashPress();
        if (notify) {
            onChange.accept(v);
        }
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        set(!value, true);
        return true;
    }

    @Override
    public boolean onKey(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_SPACE || keyCode == InputConstants.KEY_RETURN
                || keyCode == InputConstants.KEY_NUMPADENTER) {
            set(!value, true);
            return true;
        }
        return false;
    }
}
