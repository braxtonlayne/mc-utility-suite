package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.Supplier;

/**
 * One line of text clipped to its width with an ellipsis. Static lines are clipped once; dynamic lines re-read their
 * supplier in {@link #refresh(Font)} (every few ticks) and only re-clip when the text changed.
 */
public final class LabelNode extends UiNode {

    private final Supplier<String> source;
    private String raw;
    private String shown;
    private int color;
    private int bulletColor;

    public LabelNode(Font font, String text, int color, int w) {
        this(font, text, null, color, w);
    }

    public LabelNode(Font font, String text, Supplier<String> source, int color, int w) {
        super(w, 9);
        this.source = source;
        this.color = color;
        set(font, source != null ? safe(source) : text);
    }

    /** Draws a small colored dot before the text (status lines). */
    public LabelNode bullet(int argb) {
        bulletColor = argb;
        return this;
    }

    public LabelNode color(int argb) {
        color = argb;
        return this;
    }

    private void set(Font font, String text) {
        String t = text == null ? "" : text;
        if (t.equals(raw)) {
            return;
        }
        raw = t;
        shown = UiRenderUtils.ellipsize(font, t, w - (bulletColor != 0 ? 10 : 0));
    }

    public String text() {
        return raw;
    }

    @Override
    public void refresh(Font font) {
        if (source != null) {
            set(font, safe(source));
        }
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int sx, int sy, int mouseX, int mouseY) {
        if (shown.isEmpty()) {
            return;
        }
        int x = sx;
        if (bulletColor != 0) {
            g.fill(sx, sy + 2, sx + 4, sy + 6, bulletColor); // square marker, colour carries the meaning
            x += 10;
        }
        UiRenderUtils.text(g, font, shown, x, sy, color);
    }

    private static String safe(Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
