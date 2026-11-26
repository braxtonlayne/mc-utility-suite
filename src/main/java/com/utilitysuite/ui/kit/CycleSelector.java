package com.utilitysuite.ui.kit;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

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
            labelWidths[i] = UiRenderUtils.width(font, labels[i]);
            total += labelWidths[i] + SEG_PAD;
        }
        segmented = n >= 2 && n <= 4 && total <= w;
        for (int i = 0; i < n; i++) {
            int room = segmented ? w / n - 4 : w - 28;
            shown[i] = UiRenderUtils.ellipsize(font, labels[i], room);
            labelWidths[i] = UiRenderUtils.width(font, shown[i]);
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
    public void render(GuiGraphicsExtractor g, Font font, int sx, int sy, int mouseX, int mouseY) {
        if (values.isEmpty()) {
            return;
        }
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        float hv = hoverAmount(hover);
        int accent = HoloTheme.argb(HoloToken.ACCENT);
        int edge = focused ? HoloTheme.argb(HoloToken.LINE_STRONG)
                : HoloTheme.mix(HoloTheme.argb(HoloToken.LINE_FAINT), HoloTheme.argb(HoloToken.LINE), hv);
        g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.FIELD));
        HoloFrames.edge(g, sx, sy, w, h, edge);
        int ty = sy + (h - 8) / 2;
        int textSel = accent;
        int textIdle = HoloTheme.argb(HoloToken.TEXT_SECONDARY);
        int textDim = HoloTheme.argb(HoloToken.TEXT_MUTED);
        int textHot = HoloTheme.argb(HoloToken.TEXT);
        if (segmented) {
            int n = values.size();
            // the selected block and its underline slide between segments
            float pos = AnimStore.SHARED.settle(WidgetKeys.channel(key(), CH_SLIDE), index, AnimStore.TAU_NORMAL, index);
            int px0 = sx + Math.round(pos * w / n);
            int px1 = sx + Math.round((pos + 1f) * w / n);
            g.fill(Math.max(sx + 1, px0), sy + 1, Math.min(sx + w - 1, px1), sy + h - 1,
                    HoloTheme.argb(HoloToken.GLASS_RAISED));
            g.fill(Math.max(sx + 1, px0), sy + h - 3, Math.min(sx + w - 1, px1), sy + h - 1, accent);
            int hairline = HoloTheme.argb(HoloToken.LINE_FAINT);
            for (int i = 0; i < n; i++) {
                int x0 = sx + i * w / n;
                int x1 = sx + (i + 1) * w / n;
                if (i > 0) {
                    g.fill(x0, sy + 3, x0 + 1, sy + h - 3, hairline);
                }
                boolean sel = i == index;
                boolean segHover = hover && mouseX >= x0 && mouseX < x1;
                int c = sel ? textSel : segHover ? textHot : textDim;
                UiRenderUtils.text(g, font, shown[i], x0 + (x1 - x0 - labelWidths[i]) / 2, ty - 1, c);
            }
        } else {
            boolean leftHover = hover && mouseX < sx + w / 3;
            boolean rightHover = hover && !leftHover;
            UiRenderUtils.chevron(g, sx + 7, sy + (h - 5) / 2, 2, leftHover ? accent : textDim);
            UiRenderUtils.chevron(g, sx + w - 10, sy + (h - 5) / 2, 0, rightHover ? accent : textDim);
            UiRenderUtils.text(g, font, shown[index], sx + (w - labelWidths[index]) / 2, ty,
                    HoloTheme.mix(textIdle, textHot, hv));
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
        } else if (button == InputConstants.MOUSE_BUTTON_RIGHT || mouseX < sx + w / 3) {
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
        if (keyCode == InputConstants.KEY_RIGHT || keyCode == InputConstants.KEY_UP) {
            select(index + 1);
            return true;
        }
        if (keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_DOWN) {
            select(index - 1);
            return true;
        }
        return false;
    }
}
