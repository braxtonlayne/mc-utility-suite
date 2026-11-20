package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Base of every custom-drawn menu element. A node has a fixed layout box ({@code x, y, w, h}) computed when the tab is
 * built: for scrolling content it is relative to the content origin, for chrome it is absolute. The owner passes the
 * resolved screen position ({@code sx, sy}) to every call, so scrolling never touches the nodes. Rendering must not
 * allocate; text that changes is rebuilt in {@link #refresh(Font)}, which the menu calls every few ticks.
 */
public abstract class UiNode {

    public int x;
    public int y;
    public int w;
    public int h;
    public boolean focused;
    private String tooltip = "";

    protected UiNode(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public abstract void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY);

    /** True when the node reacts to the mouse (hover cursor, click sound, tooltip priority). */
    public boolean interactive() {
        return false;
    }

    /** Mouse press inside the node; return true when consumed. */
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        return false;
    }

    public void onDrag(double mouseX, double mouseY, int sx, int sy) {
    }

    public void onRelease() {
    }

    /** Keyboard input while focused; return true when consumed. */
    public boolean onKey(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    /** Whether a click gives this node keyboard focus (sliders, selectors). */
    public boolean focusable() {
        return false;
    }

    /** Re-reads dynamic text (every few ticks, never per frame). */
    public void refresh(Font font) {
    }

    public UiNode tooltip(String text) {
        tooltip = text == null ? "" : text;
        return this;
    }

    public String tooltipText() {
        return tooltip;
    }

    public final boolean contains(double mouseX, double mouseY, int sx, int sy) {
        return mouseX >= sx && mouseX < sx + w && mouseY >= sy && mouseY < sy + h;
    }

    protected static boolean inside(int mouseX, int mouseY, int sx, int sy, int w, int h) {
        return mouseX >= sx && mouseX < sx + w && mouseY >= sy && mouseY < sy + h;
    }
}
