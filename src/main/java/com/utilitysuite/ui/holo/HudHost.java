package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.IHudHost;

/**
 * The one in-game HUD host. The GUI layer that owns it ({@code SuiteHud}) runs the engine gates (hidden GUI, no
 * player) and then {@link #frame}; this class owns everything engine-free:
 * <ol>
 *     <li>the early-out chain: host disabled, Clean-Screen (only elements marked {@link #allowInCleanScreen} may
 *     draw there), and "no element wants to draw" all return before the canvas is touched, so idle and Clean-Screen
 *     cost zero draw calls;</li>
 *     <li>slot placement: elements in a slot stack by priority (higher nearer the anchor) with a {@value #GAP} px gap,
 *     top slots downward from the top edge, {@link HudSlot#BOTTOM_RIGHT} upward from the bottom,
 *     {@link HudSlot#RIGHT_MID} downward from 40 % of the height, {@link HudSlot#CROSSHAIR} at the screen centre and
 *     {@link HudSlot#FREE} at the origin;</li>
 *     <li>an appear fade ({@value #FADE_IN_S} s) and reflow (each element's y settles toward its slot position when a
 *     neighbour arrives or leaves), both through {@link AnimStore}. An element that stops wanting to draw gets no more
 *     calls at once (the contract), so leave fades belong to the element itself. Elements marked
 *     {@link #setInstant} (danger states) skip the fade: they never wait on an animation.</li>
 * </ol>
 * Registration is a start-up call on the client thread; {@link #frame} runs on the render thread and allocates
 * nothing (fixed arrays, no iterators, cached enum values).
 */
public final class HudHost implements IHudHost {

    public static final int MAX_ELEMENTS = 32;
    /** Distance of the stacks from the screen edges. */
    public static final float MARGIN = 6f;
    public static final float GAP = 4f;
    public static final float FADE_IN_S = 0.12f;

    private static final HudSlot[] SLOTS = HudSlot.values();

    private final AnimStore anim;
    private final HudElement[] elements = new HudElement[MAX_ELEMENTS];
    private final boolean[] instant = new boolean[MAX_ELEMENTS];
    private final boolean[] cleanOk = new boolean[MAX_ELEMENTS];
    private final boolean[] shown = new boolean[MAX_ELEMENTS];
    private final long[] fadeKey = new long[MAX_ELEMENTS];
    private final long[] yKey = new long[MAX_ELEMENTS];
    private int count;

    /** False switches the whole HUD off (nothing drawn, nothing measured). */
    public boolean enabled = true;
    /** Samples {@link FrameClock} at the start of each frame; tests turn it off and drive the clock themselves. */
    public boolean sampleClock = true;

    public HudHost() {
        this(AnimStore.SHARED);
    }

    public HudHost(AnimStore anim) {
        this.anim = anim;
    }

    // ---- registry ---------------------------------------------------------------------------------------------------

    @Override
    public void register(HudElement element) {
        if (element == null || indexOf(element) >= 0) {
            return;
        }
        if (count == MAX_ELEMENTS) {
            throw new IllegalStateException("HUD host is full (" + MAX_ELEMENTS + " elements)");
        }
        // keep the array sorted by priority, highest first (stable: equal priorities keep registration order)
        int i = count;
        while (i > 0 && elements[i - 1].priority() < element.priority()) {
            move(i - 1, i);
            i--;
        }
        elements[i] = element;
        instant[i] = false;
        cleanOk[i] = false;
        shown[i] = false;
        fadeKey[i] = AnimStore.key(element.id(), 0x48F1);
        yKey[i] = AnimStore.key(element.id(), 0x48F2);
        count++;
    }

    @Override
    public void unregister(HudElement element) {
        int i = indexOf(element);
        if (i < 0) {
            return;
        }
        for (int j = i; j < count - 1; j++) {
            move(j + 1, j);
        }
        count--;
        elements[count] = null;
    }

    private void move(int from, int to) {
        elements[to] = elements[from];
        instant[to] = instant[from];
        cleanOk[to] = cleanOk[from];
        shown[to] = shown[from];
        fadeKey[to] = fadeKey[from];
        yKey[to] = yKey[from];
    }

    private int indexOf(HudElement e) {
        for (int i = 0; i < count; i++) {
            if (elements[i] == e) {
                return i;
            }
        }
        return -1;
    }

    /** The element appears at full alpha at once (danger states never wait on an animation). */
    public void setInstant(HudElement element, boolean on) {
        int i = indexOf(element);
        if (i >= 0) {
            instant[i] = on;
        }
    }

    /** The element may draw while Clean-Screen hides everything else (the opt-in Clean badge only). */
    public void allowInCleanScreen(HudElement element, boolean on) {
        int i = indexOf(element);
        if (i >= 0) {
            cleanOk[i] = on;
        }
    }

    public int size() {
        return count;
    }

    // ---- frame ------------------------------------------------------------------------------------------------------

    /** True when at least one element passes the gates and wants to draw this frame. */
    public boolean wantsAny(boolean cleanScreen) {
        if (!enabled) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if ((!cleanScreen || cleanOk[i]) && elements[i].wantsDraw()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Draws one HUD frame into {@code c}. Returns false (having made no canvas call) when the gates or the elements
     * say there is nothing to draw.
     */
    public boolean frame(IHoloCanvas c, boolean cleanScreen) {
        if (!wantsAny(cleanScreen)) {
            for (int i = 0; i < count; i++) {
                shown[i] = false;
            }
            return false;
        }
        if (sampleClock) {
            FrameClock.sample();
        }
        float sw = c.screenWidth();
        float sh = c.screenHeight();
        for (HudSlot slot : SLOTS) {
            float cursor = 0f;
            for (int i = 0; i < count; i++) {
                HudElement e = elements[i];
                if (e.slot() != slot) {
                    continue;
                }
                if ((cleanScreen && !cleanOk[i]) || !e.wantsDraw()) {
                    shown[i] = false;
                    continue;
                }
                if (slot == HudSlot.CROSSHAIR) {
                    e.draw(c, sw * 0.5f, sh * 0.5f, appear(i));
                    continue;
                }
                if (slot == HudSlot.FREE) {
                    e.draw(c, 0f, 0f, appear(i));
                    continue;
                }
                float w = e.width(c);
                float h = e.height(c);
                float x;
                float ty;
                switch (slot) {
                    case TOP_LEFT -> {
                        x = MARGIN;
                        ty = MARGIN + cursor;
                    }
                    case TOP_RIGHT -> {
                        x = sw - MARGIN - w;
                        ty = MARGIN + cursor;
                    }
                    case RIGHT_MID -> {
                        x = sw - MARGIN - w;
                        ty = sh * 0.4f + cursor;
                    }
                    case BOTTOM_RIGHT -> {
                        x = sw - MARGIN - w;
                        ty = sh - MARGIN - h - cursor;
                    }
                    default -> { // TOP_CENTER
                        x = (float) Math.floor((sw - w) * 0.5f);
                        ty = MARGIN + cursor;
                    }
                }
                cursor += h + GAP;
                float alpha = appear(i);
                float y;
                if (!wasShownBefore) {
                    anim.set(yKey[i], ty);
                    y = ty;
                } else if (HoloTheme.motion == Motion.FULL) {
                    y = anim.settle(yKey[i], ty, AnimStore.TAU_NORMAL, ty);
                } else {
                    y = ty;
                }
                e.draw(c, x, Math.round(y), alpha);
            }
        }
        return true;
    }

    /** Set by {@link #appear}: whether the element was already on screen in the previous frame. */
    private boolean wasShownBefore;

    private float appear(int i) {
        wasShownBefore = shown[i];
        if (!shown[i]) {
            shown[i] = true;
            if (!instant[i]) {
                anim.set(fadeKey[i], 0f);
            }
        }
        if (instant[i]) {
            return 1f;
        }
        return anim.progress(fadeKey[i], true, FADE_IN_S);
    }
}
