package com.utilitysuite.core.api.visual;

/**
 * Counting {@link IHoloCanvas} for tests: idle and Clean-Screen tests assert {@link #calls} == 0, budget tests assert
 * {@link #quads} and {@link #textRuns}, allocation tests draw into it (it allocates nothing). Text is measured at
 * 6 px per character, numbers at 6 px per digit. The last text, token and position are kept for simple assertions.
 */
public final class RecordingCanvas implements IHoloCanvas {

    public int calls;
    public int quads;
    public int textRuns;
    public int plates;
    public int frames;
    public int ribbons;
    public int glyphs;
    public String lastText;
    public HoloToken lastToken;
    public float lastX;
    public float lastY;
    public float lastAlpha;
    public float screenW = 480f;
    public float screenH = 270f;

    public void reset() {
        calls = 0;
        quads = 0;
        textRuns = 0;
        plates = 0;
        frames = 0;
        ribbons = 0;
        glyphs = 0;
        lastText = null;
        lastToken = null;
    }

    private void note(int q, HoloToken token, float x, float y, float alpha) {
        calls++;
        quads += q;
        lastToken = token;
        lastX = x;
        lastY = y;
        lastAlpha = alpha;
    }

    @Override
    public void plate(float x, float y, float w, float h, HoloToken fill, float alpha) {
        plates++;
        note(1, fill, x, y, alpha);
    }

    @Override
    public void frame(float x, float y, float w, float h, FrameLevel level, boolean chamfer, float alpha) {
        frames++;
        note(level == FrameLevel.QUIET ? 5 : level == FrameLevel.FRAMED ? 13 : 25, HoloToken.GLASS, x, y, alpha);
    }

    @Override
    public void brackets(float x, float y, float w, float h, float arm, HoloToken token, float alpha) {
        note(8, token, x, y, alpha);
    }

    @Override
    public void hLine(float x0, float x1, float y, HoloToken token, float alpha) {
        note(1, token, x0, y, alpha);
    }

    @Override
    public void vLine(float x, float y0, float y1, HoloToken token, float alpha) {
        note(1, token, x, y0, alpha);
    }

    @Override
    public void ribbon(float x0, float y0, float x1, float y1, float width, HoloToken token, float alpha) {
        ribbons++;
        note(1, token, x0, y0, alpha);
    }

    @Override
    public void arc(float cx, float cy, float radius, float startDeg, float sweepDeg, float width, HoloToken token,
                    float alpha) {
        ribbons++;
        note(Math.max(1, (int) (Math.abs(sweepDeg) / 12f)), token, cx, cy, alpha);
    }

    @Override
    public void bar(float x, float y, float w, float h, float fraction, int segments, HoloToken fill,
                    HoloToken track, float alpha) {
        note(2 * Math.max(1, segments), fill, x, y, alpha);
    }

    @Override
    public void glyph(Glyph glyph, float x, float y, HoloToken token, float alpha) {
        glyphs++;
        note(1, token, x, y, alpha);
    }

    @Override
    public void text(TextRole role, String s, float x, float y, HoloToken token, float alpha) {
        textRuns++;
        lastText = s;
        note(0, token, x, y, alpha);
    }

    @Override
    public void number(TextRole role, int value, float x, float y, HoloToken token, float alpha) {
        textRuns++;
        note(0, token, x, y, alpha);
    }

    @Override
    public void item(String registryId, float x, float y, float alpha) {
        note(1, null, x, y, alpha);
    }

    @Override
    public float width(TextRole role, String s) {
        return s == null ? 0f : s.length() * 6f;
    }

    @Override
    public float numberWidth(TextRole role, int value) {
        int digits = value < 0 ? 2 : 1;
        for (int v = Math.abs(value); v >= 10; v /= 10) {
            digits++;
        }
        return digits * 6f;
    }

    @Override
    public float lineHeight(TextRole role) {
        return role == TextRole.MICRO ? 7f : 9f;
    }

    @Override
    public float screenWidth() {
        return screenW;
    }

    @Override
    public float screenHeight() {
        return screenH;
    }
}
