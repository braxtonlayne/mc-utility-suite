package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

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
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        position(sx, sy);
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        boolean f = box.isFocused();
        int border = f ? UiTheme.ACCENT_BORDER : hover ? UiTheme.FIELD_BORDER_HOVER : UiTheme.BORDER;
        UiRenderUtils.panel(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, UiTheme.FIELD, border);
        if (prompt) {
            UiRenderUtils.chevron(g, sx + 8, sy + (h - 5) / 2, 0, f ? UiTheme.ACCENT : UiTheme.TEXT_MUTED);
        }
        UiRenderUtils.scissor(g, sx + 1, sy + 1, Math.min(sx + w - 1, box.getX() + box.getWidth() + 1), sy + h - 1); // long hints never spill out of the frame
        box.render(g, mouseX, mouseY, 0f);
        g.disableScissor();
    }

    /** Places the cursor where the user clicked (the caller gives the box focus). */
    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        position(sx, sy);
        double cx = Math.max(box.getX(), Math.min(box.getX() + box.getWidth() - 1, mouseX));
        box.mouseClicked(cx, box.getY() + 1, button);
        return true;
    }
}
