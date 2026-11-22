package com.utilitysuite.core.api.visual;

/**
 * One piece of the in-game HUD. The host owns placement, stacking, fades, the HUD scale and opacity, and every gate
 * (module off, Clean-Screen, hidden GUI): an element is only asked to draw when it said it wants to. All methods run
 * on the render thread every frame and must be allocation free.
 */
public interface HudElement {

    /** Constant id ("status_line"). */
    String id();

    HudSlot slot();

    /** Higher draws nearer the anchor of the slot. */
    default int priority() {
        return 0;
    }

    /** False when there is nothing to show: the host then makes no call for this element (idle = nothing drawn). */
    boolean wantsDraw();

    /** Width the element needs, in canvas pixels. Not called for CROSSHAIR and FREE. */
    default float width(IHoloCanvas c) {
        return 0f;
    }

    default float height(IHoloCanvas c) {
        return 0f;
    }

    /** Draws at the position the host chose. {@code alpha} carries the appear and leave fade of the host. */
    void draw(IHoloCanvas c, float x, float y, float alpha);
}
