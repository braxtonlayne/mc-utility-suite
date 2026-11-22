package com.utilitysuite.core.api.visual;

/**
 * 2D drawing in theme tokens, in GUI pixels. The Shell implements it over the GUI buffers of the game; tests use a
 * recording fake. Every call is allocation free and none of them changes pose or scissor state. {@code alpha} is
 * 0..1 and multiplies the alpha of the token and the HUD opacity setting. Render thread only.
 */
public interface IHoloCanvas {

    /** A filled rectangle (a plate behind text). */
    void plate(float x, float y, float w, float h, HoloToken fill, float alpha);

    /** A bracket frame: fill, hairline edge and corner brackets by level; hero adds the outer glow. */
    void frame(float x, float y, float w, float h, FrameLevel level, boolean chamfer, float alpha);

    /** Four L-shaped corner brackets with the given arm length (no fill, no edge). */
    void brackets(float x, float y, float w, float h, float arm, HoloToken token, float alpha);

    void hLine(float x0, float x1, float y, HoloToken token, float alpha);

    void vLine(float x, float y0, float y1, HoloToken token, float alpha);

    /** An anti-aliased line of the given core width between two points. */
    void ribbon(float x0, float y0, float x1, float y1, float width, HoloToken token, float alpha);

    /** A circular arc from {@code startDeg} over {@code sweepDeg} (0 = up, clockwise). */
    void arc(float cx, float cy, float radius, float startDeg, float sweepDeg, float width, HoloToken token,
             float alpha);

    /** A horizontal bar filled to {@code fraction} 0..1; {@code segments} above 1 splits it with 1 px gaps. */
    void bar(float x, float y, float w, float h, float fraction, int segments, HoloToken fill, HoloToken track,
             float alpha);

    void glyph(Glyph glyph, float x, float y, HoloToken token, float alpha);

    /** Draws a string that the caller keeps (never build one per frame). */
    void text(TextRole role, String s, float x, float y, HoloToken token, float alpha);

    /** Draws an integer from the cached digit table of the Shell. */
    void number(TextRole role, int value, float x, float y, HoloToken token, float alpha);

    /** An item icon by registry id ("minecraft:iron_pickaxe"), 16 px. */
    void item(String registryId, float x, float y, float alpha);

    float width(TextRole role, String s);

    float numberWidth(TextRole role, int value);

    float lineHeight(TextRole role);

    /** Size of the GUI area in canvas pixels (after the HUD scale setting). */
    float screenWidth();

    float screenHeight();
}
