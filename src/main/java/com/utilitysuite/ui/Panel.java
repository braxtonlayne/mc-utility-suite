package com.utilitysuite.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Scrollable, row-based content area of the menu screen. Tab builders append headings, text lines and widget rows
 * top to bottom; the panel assigns each a logical y, then {@link #applyScroll} positions the widgets and hides the
 * ones outside the viewport (hidden widgets ignore clicks). Text lines are drawn by {@link #renderText}; dynamic
 * lines are re-evaluated by {@link #refreshDynamic} (the screen calls it every few ticks, never per frame).
 * <p>
 * Also the extension surface for {@link ModulePanels}: a custom module panel receives this object.
 */
public final class Panel {

    public static final int ROW = 22;
    public static final int LINE = 11;
    public static final int WIDGET_H = 20;

    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY = 0xFFA0A0A0;
    public static final int HEADING = 0xFFFFD866;
    public static final int RED = 0xFFFF6B6B;
    public static final int GREEN = 0xFF7CDB7C;
    public static final int YELLOW = 0xFFFFE066;

    private record Placed(AbstractWidget widget, int logicalY) {
    }

    private static final class Line {
        final int logicalY;
        final int x;
        final int color;
        final Supplier<String> source;
        String text;

        Line(int logicalY, int x, int color, String text, Supplier<String> source) {
            this.logicalY = logicalY;
            this.x = x;
            this.color = color;
            this.text = text;
            this.source = source;
        }
    }

    private final SuiteMenuScreen screen;
    private final Font font;
    private final int left;
    private final int top;
    private final int width;
    private final int viewHeight;
    private final List<Placed> widgets = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private int y;
    private int scroll;

    Panel(SuiteMenuScreen screen, Font font, int left, int top, int width, int viewHeight) {
        this.screen = screen;
        this.font = font;
        this.left = left;
        this.top = top;
        this.width = width;
        this.viewHeight = viewHeight;
    }

    // ---- geometry ---------------------------------------------------------------------------------------------------

    public Font font() {
        return font;
    }

    public int width() {
        return width;
    }

    public int left() {
        return left;
    }

    /** Logical height of the content so far. */
    public int contentHeight() {
        return y;
    }

    // ---- content ----------------------------------------------------------------------------------------------------

    public void heading(String text) {
        if (y > 0) {
            y += 6;
        }
        lines.add(new Line(y + 2, 0, HEADING, clip(text, width), null));
        y += LINE + 4;
    }

    public void text(String text) {
        text(text, WHITE);
    }

    public void text(String text, int color) {
        textIndented(text, color, 0);
    }

    public void textIndented(String text, int color, int indent) {
        lines.add(new Line(y, indent, color, clip(text, width - indent), null));
        y += LINE;
    }

    /** A line whose text is re-read every few ticks (e.g. "Now: Mining at Y=-54"). */
    public void dynamic(Supplier<String> source, int color) {
        String t = safe(source);
        lines.add(new Line(y, 0, color, clip(t, width), source));
        y += LINE;
    }

    public void gap(int pixels) {
        y += pixels;
    }

    /** Places a widget on the current row at {@code dx}; rows advance with {@link #nextRow()}. */
    public <T extends AbstractWidget> T place(T widget, int dx) {
        widget.setX(left + dx);
        widgets.add(new Placed(widget, y));
        return widget;
    }

    /** A label at the current row's text baseline (left of a widget). */
    public void rowLabel(String text, int dx, int maxWidth, int color) {
        lines.add(new Line(y + 6, dx, color, clip(text, maxWidth), null));
    }

    public void nextRow() {
        y += ROW;
    }

    // ---- widget helpers ---------------------------------------------------------------------------------------------

    public Button button(String label, int dx, int w, Button.OnPress onPress) {
        return place(Button.builder(Component.literal(label), onPress).bounds(0, 0, w, WIDGET_H).build(), dx);
    }

    public Button button(String label, String tooltip, int dx, int w, Button.OnPress onPress) {
        Button b = button(label, dx, w, onPress);
        if (tooltip != null && !tooltip.isEmpty()) {
            b.setTooltip(Tooltip.create(Component.literal(tooltip)));
        }
        return b;
    }

    /** ON/OFF button showing "label: ON". */
    public CycleButton<Boolean> toggle(String label, boolean value, int dx, int w, Consumer<Boolean> onChange) {
        CycleButton<Boolean> b = CycleButton.onOffBuilder(value)
                .create(0, 0, w, WIDGET_H, Component.literal(label), (btn, v) -> onChange.accept(v));
        return place(b, dx);
    }

    /**
     * An edit box whose text survives rebuilds and reopening the menu (stored in the screen's state under
     * {@code stateKey}); focus is restored after a rebuild.
     */
    public EditBox textField(String stateKey, String hint, int dx, int w) {
        EditBox box = new EditBox(font, 0, 0, w, WIDGET_H, Component.literal(hint));
        box.setMaxLength(256);
        box.setValue(screen.state(stateKey, ""));
        box.setHint(Component.literal(hint).withColor(0xFF707070));
        box.setResponder(v -> screen.setState(stateKey, v));
        screen.registerTextField(box, stateKey);
        return place(box, dx);
    }

    // ---- screen state -----------------------------------------------------------------------------------------------

    public String state(String key, String def) {
        return screen.state(key, def);
    }

    public void setState(String key, String value) {
        screen.setState(key, value);
    }

    public boolean flag(String key) {
        return "1".equals(screen.state(key, ""));
    }

    public void setFlag(String key, boolean value) {
        screen.setState(key, value ? "1" : "");
    }

    /** Rebuilds the screen's widgets on the next tick (after a structural change). */
    public void rebuild() {
        screen.requestRebuild();
    }

    /** Shows a short status message at the bottom of the menu. */
    public void toast(String message) {
        screen.setStatus(message);
    }

    // ---- layout -----------------------------------------------------------------------------------------------------

    List<AbstractWidget> widgets() {
        List<AbstractWidget> out = new ArrayList<>(widgets.size());
        for (Placed p : widgets) {
            out.add(p.widget());
        }
        return out;
    }

    int maxScroll() {
        return Math.max(0, y - viewHeight + 4);
    }

    int scroll() {
        return scroll;
    }

    void setScroll(int value) {
        scroll = Math.max(0, Math.min(maxScroll(), value));
        applyScroll();
    }

    void applyScroll() {
        int bottom = top + viewHeight;
        for (Placed p : widgets) {
            AbstractWidget w = p.widget();
            int wy = top + p.logicalY() - scroll;
            w.setY(wy);
            w.visible = wy >= top && wy + w.getHeight() <= bottom;
        }
    }

    void refreshDynamic() {
        for (Line l : lines) {
            if (l.source != null) {
                l.text = clip(safe(l.source), width - l.x);
            }
        }
    }

    void renderText(GuiGraphics g) {
        int bottom = top + viewHeight;
        for (Line l : lines) {
            int ly = top + l.logicalY - scroll;
            if (ly < top || ly + 9 > bottom || l.text.isEmpty()) {
                continue;
            }
            g.drawString(font, l.text, left + l.x, ly, l.color);
        }
    }

    private String clip(String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (font.width(s) <= maxWidth) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("…"))) + "…";
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
