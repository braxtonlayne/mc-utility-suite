package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 2 px hairline rail with an accent fill, a 2x10 thumb bar, a tick at the default and a right-aligned value readout. Click or drag anywhere on the
 * track; arrow keys step while focused. Value math lives in the {@link Model} (snapping, clamping, formatting), so the
 * widget only maps pixels to a 0..1 position.
 */
public final class ModernSlider extends UiNode {

    public static final int H = 14;
    private static final int THUMB = 10;

    /** Value source and sink of a slider. */
    public interface Model {
        /** Current position 0..1. */
        double position();

        /** Applies a position 0..1 (snapped by the model); returns true when the value changed. */
        boolean setPosition(double position);

        /** Steps one increment up (+1) or down (-1); returns true when the value changed. */
        boolean step(int direction);

        /** Readout text for the current value. */
        String text();

        /** The widest readout this slider can show (reserves the label column). */
        String widestText();

        /** Position 0..1 of the default value, for the tick mark; negative when there is none. */
        default double defaultPosition() {
            return -1.0;
        }
    }

    private final Model model;
    private final int readoutW;
    private String readout;
    private int readoutWidth;
    private boolean dragging;

    public ModernSlider(Font font, Model model, int w) {
        super(w, H);
        this.model = model;
        this.readoutW = Math.min(w / 3, Math.max(40, font.width(model.widestText()) + 8)); // aligned tracks
        updateText(font);
    }

    private void updateText(Font font) {
        readout = model.text();
        readoutWidth = font.width(readout);
    }

    private int trackX(int sx) {
        return sx + THUMB / 2;
    }

    private int trackW() {
        return Math.max(10, w - readoutW - THUMB);
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public boolean focusable() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        if (dirty) {
            dirty = false;
            updateText(font); // only after a change
        }
        boolean hover = dragging || inside(mouseX, mouseY, sx, sy, w - readoutW, h);
        float hv = hoverAmount(hover);
        int tx = trackX(sx);
        int tw = trackW();
        int cy = sy + h / 2;
        double p = model.position();
        int fillW = (int) Math.round(p * tw);
        int accent = HoloTheme.argb(HoloToken.ACCENT);
        // 2 px rail and fill
        g.fill(tx, cy - 1, tx + tw, cy + 1, HoloTheme.mix(HoloTheme.argb(HoloToken.LINE_FAINT),
                HoloTheme.argb(HoloToken.LINE), hv));
        if (fillW > 0) {
            g.fill(tx, cy - 1, tx + fillW, cy + 1, accent);
        }
        // a tick at the default value
        double dp = model.defaultPosition();
        if (dp >= 0.0 && dp <= 1.0) {
            int dx = tx + (int) Math.round(dp * tw);
            g.fill(dx, cy + 2, dx + 1, cy + 5, HoloTheme.argb(HoloToken.TEXT_MUTED));
        }
        // 2x10 thumb bar
        int thumbX = tx + fillW - 1;
        int thumb = HoloTheme.mix(accent, HoloTheme.argb(HoloToken.TEXT), hv);
        g.fill(thumbX, cy - THUMB / 2, thumbX + 2, cy + THUMB / 2, thumb);
        if (focused || dragging) {
            HoloFrames.brackets(g, thumbX - 3, cy - THUMB / 2 - 1, 8, THUMB + 2, 2,
                    HoloTheme.argb(HoloToken.LINE_STRONG));
        }
        UiRenderUtils.text(g, font, readout, sx + w - readoutWidth, cy - 4, hover || focused
                ? HoloTheme.argb(HoloToken.TEXT) : HoloTheme.argb(HoloToken.TEXT_SECONDARY));
    }

    private void apply(double mouseX, int sx) {
        double p = (mouseX - trackX(sx)) / (double) trackW();
        if (model.setPosition(Math.max(0.0, Math.min(1.0, p)))) {
            dirty = true;
        }
    }

    private boolean dirty;

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (button != 0 || mouseX >= sx + w - readoutW + 2) {
            return button == 0;
        }
        dragging = true;
        apply(mouseX, sx);
        return true;
    }

    @Override
    public void onDrag(double mouseX, double mouseY, int sx, int sy) {
        if (dragging) {
            apply(mouseX, sx);
        }
    }

    @Override
    public void onRelease() {
        dragging = false;
    }

    @Override
    public boolean onKey(int keyCode, int scanCode, int modifiers) {
        int dir = switch (keyCode) {
            case 262, 265 -> 1;   // right, up
            case 263, 264 -> -1;  // left, down
            default -> 0;
        };
        if (dir == 0) {
            return false;
        }
        if (model.step(dir)) {
            dirty = true;
        }
        return true;
    }

    @Override
    public void refresh(Font font) {
        updateText(font);
    }
}
