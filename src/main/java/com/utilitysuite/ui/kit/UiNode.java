package com.utilitysuite.ui.kit;

import com.utilitysuite.ui.holo.AnimStore;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

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

    // ---- animation (AnimStore.SHARED; keyed so state survives a rebuild) ---------------------------------------------

    protected static final int CH_HOVER = 1;
    protected static final int CH_PRESS = 2;
    protected static final int CH_SLIDE = 3;
    protected static final int CH_VALUE = 4;

    private long animId;

    /** Sets the stable animation key of this widget (see {@link WidgetKeys}). */
    public UiNode animId(long key) {
        animId = key;
        return this;
    }

    /** The stable key; 0 until set. */
    public final long animId() {
        return animId;
    }

    /** Assigns a positional key when nobody gave this node one (called by the panel when the node is placed). */
    public final void ensureAnimId() {
        if (animId == 0L) {
            animId = WidgetKeys.positional(getClass(), x, y);
        }
    }

    /** The key used for drawing: a keyed one, or an identity key for chrome nodes that never saw a panel. */
    protected final long key() {
        if (animId == 0L) {
            animId = WidgetKeys.positional(getClass(), System.identityHashCode(this), 0);
        }
        return animId;
    }

    /** Hover amount 0..1, settled fast. Live from the first frame. */
    protected final float hoverAmount(boolean hover) {
        return AnimStore.SHARED.settle(WidgetKeys.channel(key(), CH_HOVER), hover ? 1f : 0f, AnimStore.TAU_FAST, 0f);
    }

    /** Press flash 0..1: jumps to 1 on {@link #flashPress()} and settles back. */
    protected final float pressAmount() {
        return AnimStore.SHARED.settle(WidgetKeys.channel(key(), CH_PRESS), 0f, AnimStore.TAU_NORMAL, 0f);
    }

    protected final void flashPress() {
        AnimStore.SHARED.set(WidgetKeys.channel(key(), CH_PRESS), 1f);
    }

    protected UiNode(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public abstract void render(GuiGraphicsExtractor g, Font font, int sx, int sy, int mouseX, int mouseY);

    /** True when the node reacts to the mouse (hover cursor, click sound, tooltip priority). */
    public boolean interactive() {
        return false;
    }

    /**
     * The button passed to {@link #onClick}: 26.3 SDL numbering, as {@code MouseButtonEvent.button()} reports it
     * ({@code InputConstants.MOUSE_BUTTON_LEFT} = 1, middle 2, right 3; 1.21.1 used GLFW 0 = left, 1 = right).
     * Screens may pass {@code event.button()} straight through; this helper only names that.
     */
    public static int button(net.minecraft.client.input.MouseButtonEvent event) {
        return event.button();
    }

    /**
     * Mouse press inside the node (button: SDL, {@code InputConstants.MOUSE_BUTTON_LEFT} = 1, right 3); return true
     * when consumed.
     */
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        return false;
    }

    public void onDrag(double mouseX, double mouseY, int sx, int sy) {
    }

    public void onRelease() {
    }

    /**
     * Keyboard input while focused; return true when consumed. 26.3: {@code keyCode} is the SDL scancode
     * ({@code KeyEvent.key()}, compare with {@code InputConstants.KEY_*}), {@code scanCode} carries the SDL keycode
     * ({@code KeyEvent.keycode()}).
     */
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
