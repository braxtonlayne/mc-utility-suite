package com.utilitysuite.ui.kit;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 28x14 animated switch: accent track with the knob on the right when on, muted track when off. */
public final class ToggleSwitch extends UiNode {

    public static final int W = 28;
    public static final int H = 14;

    /** Running animations by key, so a switch rebuilt right after a click keeps sliding (written on click only). */
    private static final Map<String, long[]> RECENT = new HashMap<>();

    private boolean value;
    private final Consumer<Boolean> onChange;
    private long animStart = Long.MIN_VALUE;
    private float animFrom;
    private String animKey;

    public ToggleSwitch(boolean value, Consumer<Boolean> onChange) {
        super(W, H);
        this.value = value;
        this.onChange = onChange;
    }

    /** Continues an animation started by a previous switch with the same key (menu rebuilds). */
    public ToggleSwitch animKey(String key) {
        animKey = key;
        long[] a = key == null ? null : RECENT.get(key);
        if (a != null && Util.getMillis() - a[0] < UiTheme.TOGGLE_MS && (a[2] == 1) == value) {
            animStart = a[0];
            animFrom = Float.intBitsToFloat((int) a[1]);
        }
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

    /** Knob position 0 (off) .. 1 (on), eased over {@link UiTheme#TOGGLE_MS}. */
    private float position() {
        float target = value ? 1f : 0f;
        if (animStart == Long.MIN_VALUE) {
            return target;
        }
        float t = (Util.getMillis() - animStart) / (float) UiTheme.TOGGLE_MS;
        if (t >= 1f) {
            animStart = Long.MIN_VALUE;
            return target;
        }
        return animFrom + (target - animFrom) * UiRenderUtils.easeOut(t);
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        float p = position();
        int off = hover ? 0xFF474D5B : UiTheme.TRACK_OFF;
        int on = hover ? UiTheme.ACCENT_HOVER : UiTheme.ACCENT;
        UiRenderUtils.roundedRect(g, sx, sy, W, H, H / 2, UiTheme.mix(off, on, p));
        if (focused) {
            UiRenderUtils.roundedOutline(g, sx - 1, sy - 1, W + 2, H + 2, H / 2 + 1, UiTheme.ACCENT_BORDER);
        }
        int knobX = sx + 2 + Math.round(p * (W - H));
        UiRenderUtils.dot(g, knobX, sy + 2, H - 4, UiTheme.KNOB);
    }

    public void set(boolean v, boolean notify) {
        if (v == value) {
            return;
        }
        animFrom = position();
        animStart = Util.getMillis();
        value = v;
        if (animKey != null) {
            if (RECENT.size() > 32) {
                RECENT.clear();
            }
            RECENT.put(animKey, new long[]{animStart, Float.floatToIntBits(animFrom), v ? 1 : 0});
        }
        if (notify) {
            onChange.accept(v);
        }
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (button != 0) {
            return false;
        }
        set(!value, true);
        return true;
    }

    @Override
    public boolean onKey(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 32 || keyCode == 257 || keyCode == 335) {
            set(!value, true);
            return true;
        }
        return false;
    }
}
