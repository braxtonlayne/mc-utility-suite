package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Choice among a few values. When every option fits it is a segmented control (click a segment); otherwise a field
 * with chevrons on both sides (left third = previous, rest = next, right click = previous). Arrow keys step while
 * focused. Labels are computed once at construction.
 */
public final class CycleSelector<E> extends UiNode {

    public static final int H = 16;
    private static final int SEG_PAD = 12;

    private final List<E> values;
    private final String[] labels;
    private final int[] labelWidths;
    private final String[] shown;
    private final Consumer<E> onChange;
    private final boolean segmented;
    private int index;

    public CycleSelector(Font font, List<E> values, E current, Function<E, String> label, int w, Consumer<E> onChange) {
        super(w, H);
        this.values = List.copyOf(values);
        this.onChange = onChange;
        int n = this.values.size();
        labels = new String[n];
        labelWidths = new int[n];
        shown = new String[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            labels[i] = label.apply(this.values.get(i));
            labelWidths[i] = font.width(labels[i]);
            total += labelWidths[i] + SEG_PAD;
        }
        segmented = n >= 2 && n <= 4 && total <= w;
        for (int i = 0; i < n; i++) {
            int room = segmented ? w / n - 4 : w - 28;
            shown[i] = UiRenderUtils.ellipsize(font, labels[i], room);
            labelWidths[i] = font.width(shown[i]);
        }
        index = Math.max(0, this.values.indexOf(current));
    }

    public E value() {
        return values.isEmpty() ? null : values.get(index);
    }

    public boolean isSegmented() {
        return segmented;
    }

    @Override
    public boolean interactive() {
        return !values.isEmpty();
    }

    @Override
    public boolean focusable() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        if (values.isEmpty()) {
            return;
        }
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        int border = focused ? UiTheme.ACCENT_BORDER : hover ? UiTheme.FIELD_BORDER_HOVER : UiTheme.BORDER;
        UiRenderUtils.panel(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, UiTheme.FIELD, border);
        int ty = sy + (h - 8) / 2;
        if (segmented) {
            int n = values.size();
            for (int i = 0; i < n; i++) {
                int x0 = sx + i * w / n;
                int x1 = sx + (i + 1) * w / n;
                boolean sel = i == index;
                boolean segHover = hover && mouseX >= x0 && mouseX < x1;
                if (sel) {
                    UiRenderUtils.roundedRect(g, x0 + 2, sy + 2, x1 - x0 - 4, h - 4, 3, 0xFF263445);
                    UiRenderUtils.roundedOutline(g, x0 + 2, sy + 2, x1 - x0 - 4, h - 4, 3, UiTheme.ACCENT_BORDER);
                } else if (segHover) {
                    UiRenderUtils.roundedRect(g, x0 + 2, sy + 2, x1 - x0 - 4, h - 4, 3, UiTheme.CONTROL);
                }
                int c = sel ? UiTheme.ACCENT : segHover ? UiTheme.TEXT : UiTheme.TEXT_DIM;
                UiRenderUtils.text(g, font, shown[i], x0 + (x1 - x0 - labelWidths[i]) / 2, ty, c);
            }
        } else {
            boolean leftHover = hover && mouseX < sx + w / 3;
            boolean rightHover = hover && !leftHover;
            UiRenderUtils.chevron(g, sx + 7, sy + (h - 5) / 2, 2, leftHover ? UiTheme.ACCENT : UiTheme.TEXT_MUTED);
            UiRenderUtils.chevron(g, sx + w - 10, sy + (h - 5) / 2, 0, rightHover ? UiTheme.ACCENT : UiTheme.TEXT_MUTED);
            UiRenderUtils.text(g, font, shown[index], sx + (w - labelWidths[index]) / 2, ty,
                    hover ? UiTheme.TEXT : UiTheme.TEXT_SECONDARY);
        }
    }

    private void select(int i) {
        int n = values.size();
        int next = ((i % n) + n) % n;
        if (next != index) {
            index = next;
            onChange.accept(values.get(index));
        }
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (values.isEmpty()) {
            return false;
        }
        if (segmented) {
            int i = (int) ((mouseX - sx) * values.size() / w);
            select(Math.max(0, Math.min(values.size() - 1, i)));
        } else if (button == 1 || mouseX < sx + w / 3) {
            select(index - 1);
        } else {
            select(index + 1);
        }
        return true;
    }

    @Override
    public boolean onKey(int keyCode, int scanCode, int modifiers) {
        if (values.isEmpty()) {
            return false;
        }
        if (keyCode == 262 || keyCode == 265) {
            select(index + 1);
            return true;
        }
        if (keyCode == 263 || keyCode == 264) {
            select(index - 1);
            return true;
        }
        return false;
    }
}
