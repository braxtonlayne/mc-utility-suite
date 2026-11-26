package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Custom-drawn frame around a vanilla {@link EditBox} whose own background is disabled ({@code setBordered(false)}).
 * The box stays a child of the screen for keyboard input and focus; this node positions it each frame (two int writes)
 * and renders it inside the frame. An optional leading glyph and a reserved right margin (for an inline action) are
 * supported for the goal bar.
 */
public final class StyledTextField extends UiNode {

    public static final int H = 18;

    private final EditBox box;
    private final String stateKey;
    private int padLeft = 6;
    private int padRight = 6;
    private boolean prompt;

    public StyledTextField(Font font, String stateKey, String hint, int w, int h) {
        super(w, h);
        this.stateKey = stateKey;
        if (stateKey != null) {
            animId(WidgetKeys.named("field." + stateKey)); // stable across rebuilds
        }
        box = new EditBox(font, 0, 0, Math.max(10, w - 12), 10, Component.literal(hint));
        box.setBordered(false);
        box.setTextShadow(false);
        box.setTextColor(UiTheme.TEXT);
        box.setTextColorUneditable(UiTheme.TEXT_MUTED);
        box.setHint(Component.literal(hint).withColor(UiTheme.TEXT_MUTED));
    }

    public EditBox box() {
        return box;
    }

    private boolean reverting;
    private String lastAccepted = "";

    /**
     * A responder behind an input filter (26.3 {@code EditBox} has no {@code setFilter}): an edit the filter rejects
     * is rolled back to the last accepted text before {@code responder} hears of it. Call after the initial
     * {@code setValue}.
     */
    public StyledTextField filteredResponder(Predicate<String> filter, Consumer<String> responder) {
        lastAccepted = box.getValue();
        box.setResponder(t -> {
            if (reverting) {
                return;
            }
            if (!filter.test(t)) {
                int cursor = Math.max(0, Math.min(lastAccepted.length(), box.getCursorPosition() - 1));
                reverting = true;
                try {
                    box.setValue(lastAccepted);
                    box.setCursorPosition(cursor);
                    box.setHighlightPos(cursor);
                } finally {
                    reverting = false;
                }
                return;
            }
            lastAccepted = t;
            responder.accept(t);
        });
        return this;
    }

    public String stateKey() {
        return stateKey;
    }

    /** Draws an accent prompt chevron before the text (goal bar). */
    public StyledTextField prompt(boolean v) {
        prompt = v;
        padLeft = v ? 18 : 6;
        box.setWidth(Math.max(10, w - padLeft - padRight));
        return this;
    }

    /** Reserves {@code px} on the right for an inline action drawn by the owner. */
    public StyledTextField reserveRight(int px) {
        padRight = 6 + px;
        box.setWidth(Math.max(10, w - padLeft - padRight));
        return this;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    /** Moves the edit box to the frame's current screen position (call before input dispatch and rendering). */
    public void position(int sx, int sy) {
        box.setX(sx + padLeft);
        box.setY(sy + (h - 8) / 2);
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int sx, int sy, int mouseX, int mouseY) {
        position(sx, sy);
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        boolean f = box.isFocused();
        float hv = hoverAmount(hover);
        float fv = AnimStore.SHARED.settle(WidgetKeys.channel(key(), CH_VALUE), f ? 1f : 0f, AnimStore.TAU_FAST, 0f);
        g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.FIELD));
        HoloFrames.edge(g, sx, sy, w, h, HoloTheme.mix(HoloTheme.argb(HoloToken.LINE_FAINT),
                HoloTheme.argb(HoloToken.LINE), hv));
        // the bottom edge carries the focus: LINE, going LINE_STRONG while focused
        int bottom = HoloTheme.mix(HoloTheme.argb(HoloToken.LINE), HoloTheme.argb(HoloToken.LINE_STRONG), fv);
        g.fill(sx, sy + h - 1, sx + w, sy + h, bottom);
        if (fv > 0.01f) {
            g.fill(sx, sy + h - 2, sx + w, sy + h - 1, HoloTheme.fade(bottom, 0.45f * fv));
        }
        if (prompt) {
            UiRenderUtils.chevron(g, sx + 8, sy + (h - 5) / 2, 0, f ? HoloTheme.argb(HoloToken.ACCENT)
                    : HoloTheme.argb(HoloToken.TEXT_MUTED));
        }
        UiRenderUtils.scissor(g, sx + 1, sy + 1, Math.min(sx + w - 1, box.getX() + box.getWidth() + 1), sy + h - 1); // long hints never spill out of the frame
        box.extractRenderState(g, mouseX, mouseY, 0f);
        g.disableScissor();
    }

    /** Places the cursor where the user clicked (the caller gives the box focus). */
    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        position(sx, sy);
        double cx = Math.max(box.getX(), Math.min(box.getX() + box.getWidth() - 1, mouseX));
        box.mouseClicked(new MouseButtonEvent(cx, box.getY() + 1, new MouseButtonInfo(button, 0)), false);
        return true;
    }
}
