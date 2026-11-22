package com.utilitysuite.ui.holo;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.visual.EffectLevel;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.Glyph;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.TextRole;
import com.utilitysuite.ui.kit.ItemIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;

/**
 * {@link IHoloCanvas} over {@link GuiGraphics}: the in-game HUD kit. Rectangles, hairlines, frames and brackets are
 * plain GUI fills (batched by the game); everything that is not axis-aligned goes through one soft-ribbon primitive,
 * a quad whose U coordinate crosses a falloff row of the {@link HoloAtlas}, so lines and arcs are anti-aliased and
 * glow for free. Vertices are written straight to the {@link VertexConsumer} with the current pose matrix.
 * <p>
 * Rules: no {@code pushPose} (the HUD scale is applied once per frame by {@link #begin}/{@link #end} on the existing
 * matrix, restored from a saved copy), nothing allocated per call by this class (the game's own text and item paths
 * allocate internally), {@link HoloTheme#hudOpacity} multiplied into every alpha, and
 * {@link EffectLevel#OFF} = flat fills and plain 1 px lines. Render thread only.
 */
public final class HoloKit implements IHoloCanvas {

    private final Sinks sinks = new Sinks();

    private GuiGraphics g;
    private Font font;
    private float scale = 1f;
    private boolean scaled;
    private final Matrix4f saved = new Matrix4f();
    private VertexConsumer vc;
    private Matrix4f matrix;
    private int digitW = -1;
    private final String[] numberCache = new String[NUM_MAX + NUM_NEG];

    private static final int NUM_MAX = 10000;
    private static final int NUM_NEG = 1000;
    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "-"};

    /** Starts a HUD frame: binds the graphics and applies the HUD scale to the current pose (no push). */
    public void begin(GuiGraphics graphics) {
        g = graphics;
        font = Minecraft.getInstance().font;
        if (digitW < 0) {
            digitW = font.width("0");
        }
        scale = HoloTheme.hudScale;
        scaled = scale < 0.99f || scale > 1.01f;
        if (scaled) {
            Matrix4f m = graphics.pose().last().pose();
            saved.set(m);
            m.scale(scale, scale, 1f);
        } else {
            scale = 1f;
        }
    }

    /** Ends the frame and restores the pose. */
    public void end() {
        if (scaled && g != null) {
            g.pose().last().pose().set(saved);
        }
        scaled = false;
        g = null;
        vc = null;
    }

    private static float op(float alpha) {
        return alpha * HoloTheme.hudOpacity;
    }

    private static int ri(float v) {
        return Math.round(v);
    }

    // ---- fills ------------------------------------------------------------------------------------------------------

    private void fill(int x0, int y0, int x1, int y1, int argb) {
        if (x1 > x0 && y1 > y0 && (argb >>> 24) != 0) {
            g.fill(x0, y0, x1, y1, argb);
        }
    }

    @Override
    public void plate(float x, float y, float w, float h, HoloToken fill, float alpha) {
        int x0 = ri(x);
        int y0 = ri(y);
        fill(x0, y0, x0 + ri(w), y0 + ri(h), HoloTheme.argb(fill, op(alpha)));
    }

    @Override
    public void frame(float x, float y, float w, float h, FrameLevel level, boolean chamfer, float alpha) {
        float a = op(alpha);
        if (a <= 0f) {
            return;
        }
        HoloFrames.frame(g, ri(x), ri(y), ri(w), ri(h), level, chamfer, HoloToken.GLASS_HUD, HoloToken.LINE_STRONG, a);
    }

    @Override
    public void brackets(float x, float y, float w, float h, float arm, HoloToken token, float alpha) {
        Geometry.brackets(sinks, ri(x), ri(y), ri(w), ri(h), Math.max(1, ri(arm)), HoloTheme.argb(token, op(alpha)));
    }

    @Override
    public void hLine(float x0, float x1, float y, HoloToken token, float alpha) {
        int a = ri(Math.min(x0, x1));
        int b = ri(Math.max(x0, x1));
        int yy = ri(y);
        fill(a, yy, b, yy + 1, HoloTheme.argb(token, op(alpha)));
    }

    @Override
    public void vLine(float x, float y0, float y1, HoloToken token, float alpha) {
        int a = ri(Math.min(y0, y1));
        int b = ri(Math.max(y0, y1));
        int xx = ri(x);
        fill(xx, a, xx + 1, b, HoloTheme.argb(token, op(alpha)));
    }

    @Override
    public void bar(float x, float y, float w, float h, float fraction, int segments, HoloToken fillToken,
                    HoloToken track, float alpha) {
        float a = op(alpha);
        Geometry.bar(sinks, ri(x), ri(y), ri(w), ri(h), fraction, segments, HoloTheme.argb(fillToken, a),
                HoloTheme.argb(track, a));
    }

    // ---- ribbons ----------------------------------------------------------------------------------------------------

    /** Routes the engine-free emitters to the GUI buffers (one instance, no per-call objects). */
    private final class Sinks implements Geometry.VertexSink, Geometry.FillSink {
        @Override
        public void vertex(float x, float y, float u, float v, int argb) {
            vc.addVertex(matrix, x, y, 0f).setUv(u, v).setColor(argb);
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            HoloKit.this.fill(x0, y0, x1, y1, argb);
        }
    }

    private boolean beginRibbons() {
        if (HoloTheme.effects == EffectLevel.OFF) {
            return false;
        }
        vc = g.bufferSource().getBuffer(HoloRenderTypes.gui());
        matrix = g.pose().last().pose();
        return true;
    }

    @Override
    public void ribbon(float x0, float y0, float x1, float y1, float width, HoloToken token, float alpha) {
        int argb = HoloTheme.argb(token, op(alpha));
        if ((argb >>> 24) == 0) {
            return;
        }
        if (!beginRibbons()) {
            // flat look: axis-aligned lines as fills, anything else as a hard 1 px ribbon
            if (y0 == y1) {
                fill(ri(Math.min(x0, x1)), ri(y0), ri(Math.max(x0, x1)), ri(y0) + 1, argb);
                return;
            }
            if (x0 == x1) {
                fill(ri(x0), ri(Math.min(y0, y1)), ri(x0) + 1, ri(Math.max(y0, y1)), argb);
                return;
            }
            vc = g.bufferSource().getBuffer(HoloRenderTypes.gui());
            matrix = g.pose().last().pose();
            Geometry.ribbon(sinks, x0, y0, x1, y1, 1f, argb, Geometry.ROW_HARD);
            return;
        }
        Geometry.ribbon(sinks, x0, y0, x1, y1, width, argb, Geometry.ROW_SOFT);
        if (HoloTheme.effects == EffectLevel.FULL) {
            Geometry.ribbon(sinks, x0, y0, x1, y1, width * 3f, HoloTheme.fade(argb, 0.5f), Geometry.ROW_GLOW);
        }
    }

    @Override
    public void arc(float cx, float cy, float radius, float startDeg, float sweepDeg, float width, HoloToken token,
                    float alpha) {
        int argb = HoloTheme.argb(token, op(alpha));
        if ((argb >>> 24) == 0 || radius <= 0f) {
            return;
        }
        vc = g.bufferSource().getBuffer(HoloRenderTypes.gui());
        matrix = g.pose().last().pose();
        if (HoloTheme.effects == EffectLevel.OFF) {
            Geometry.arc(sinks, cx, cy, radius, startDeg, sweepDeg, 1f, argb, Geometry.ROW_HARD);
            return;
        }
        Geometry.arc(sinks, cx, cy, radius, startDeg, sweepDeg, width, argb, Geometry.ROW_SOFT);
        if (HoloTheme.effects == EffectLevel.FULL) {
            Geometry.arc(sinks, cx, cy, radius, startDeg, sweepDeg, width * 3f, HoloTheme.fade(argb, 0.5f),
                    Geometry.ROW_GLOW);
        }
    }

    @Override
    public void glyph(Glyph glyph, float x, float y, HoloToken token, float alpha) {
        int argb = HoloTheme.argb(token, op(alpha));
        if ((argb >>> 24) == 0) {
            return;
        }
        vc = g.bufferSource().getBuffer(HoloRenderTypes.glyphs());
        matrix = g.pose().last().pose();
        Geometry.glyph(sinks, glyph.ordinal(), ri(x), ri(y), argb);
    }

    // ---- text -------------------------------------------------------------------------------------------------------

    /** Text colour, or 0 when too faint to draw (the font turns alpha below 4 into fully opaque). */
    private static int textArgb(HoloToken token, float alpha) {
        int argb = HoloTheme.argb(token, op(alpha));
        return (argb >>> 24) < 4 ? 0 : argb;
    }

    @Override
    public void text(TextRole role, String s, float x, float y, HoloToken token, float alpha) {
        int argb = textArgb(token, alpha);
        if (argb == 0 || s == null || s.isEmpty()) {
            return;
        }
        g.drawString(font, s, x, y, argb, false);
    }

    @Override
    public void number(TextRole role, int value, float x, float y, HoloToken token, float alpha) {
        int argb = textArgb(token, alpha);
        if (argb == 0) {
            return;
        }
        String s = cachedNumber(value);
        if (s != null) {
            g.drawString(font, s, x, y, argb, false);
            return;
        }
        // out of the cached range: digit by digit at a fixed (tabular) advance, nothing allocated
        float cx = x;
        long v = value;
        if (v < 0) {
            g.drawString(font, DIGITS[10], cx, y, argb, false);
            cx += digitW;
            v = -v;
        }
        long div = 1;
        while (div * 10 <= v) {
            div *= 10;
        }
        for (; div > 0; div /= 10) {
            g.drawString(font, DIGITS[(int) ((v / div) % 10)], cx, y, argb, false);
            cx += digitW;
        }
    }

    /** Cached string of a value in -999..9999 (built the first time it is drawn), else null. */
    private String cachedNumber(int value) {
        int idx = value >= 0 ? value : NUM_MAX - value - 1;
        if (value >= NUM_MAX || value < -NUM_NEG + 1 || idx >= numberCache.length) {
            return null;
        }
        String s = numberCache[idx];
        if (s == null) {
            s = Integer.toString(value);
            numberCache[idx] = s;
        }
        return s;
    }

    @Override
    public void item(String registryId, float x, float y, float alpha) {
        // items cannot fade: drawn while the element is at least half visible
        if (registryId == null || registryId.isEmpty() || op(alpha) < 0.5f) {
            return;
        }
        g.renderItem(ItemIcons.forId(registryId, Items.PAPER), ri(x), ri(y));
    }

    @Override
    public float width(TextRole role, String s) {
        return s == null || s.isEmpty() ? 0f : font.width(s);
    }

    @Override
    public float numberWidth(TextRole role, int value) {
        int digits = value < 0 ? 2 : 1;
        for (int v = Math.abs(value); v >= 10; v /= 10) {
            digits++;
        }
        return digits * (float) digitW;
    }

    @Override
    public float lineHeight(TextRole role) {
        return HoloTheme.lineHeight(role);
    }

    @Override
    public float screenWidth() {
        return g.guiWidth() / scale;
    }

    @Override
    public float screenHeight() {
        return g.guiHeight() / scale;
    }

    /**
     * The engine-free half of the kit: atlas layout and the emitters for brackets, bars, ribbons, arcs and glyphs,
     * writing to small sink interfaces so tests count quads and vertices without a GL context. Allocation free.
     */
    public static final class Geometry {

        public static final int ATLAS_W = 256;
        public static final int ATLAS_H = 16;
        public static final int ROW_HARD = 0;
        public static final int ROW_SOFT = 1;
        public static final int ROW_GLOW = 2;
        public static final int GLOW_X = 16;
        public static final int GLYPH_X = 32;
        public static final int GLYPH_Y = 8;
        public static final int GLYPH_STEP = 8;
        /** Arcs are cut into segments of about this many degrees (at most {@link #MAX_ARC_SEGMENTS}). */
        public static final float ARC_STEP_DEG = 6f;
        public static final int MAX_ARC_SEGMENTS = 64;

        private static final int TABLE = 256;
        private static final float[] SIN = new float[TABLE];
        private static final float[] COS = new float[TABLE];

        static {
            for (int i = 0; i < TABLE; i++) {
                double a = i * (Math.PI * 2.0 / TABLE);
                SIN[i] = (float) Math.sin(a);
                COS[i] = (float) Math.cos(a);
            }
        }

        private Geometry() {
        }

        /** Receives quad vertices (four per quad, in order). */
        public interface VertexSink {
            void vertex(float x, float y, float u, float v, int argb);
        }

        /** Receives axis-aligned filled rectangles. */
        public interface FillSink {
            void fill(int x0, int y0, int x1, int y1, int argb);
        }

        /** V coordinate of the centre of a falloff row (between its two duplicated texel rows). */
        public static float rowV(int row) {
            return (row * 2 + 1f) / ATLAS_H;
        }

        /** Four L-shaped brackets on the edge of the rectangle: 8 fills. */
        public static void brackets(FillSink s, int x, int y, int w, int h, int arm, int argb) {
            if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
                return;
            }
            int a = Math.min(arm, Math.min(w, h));
            int x1 = x + w;
            int y1 = y + h;
            s.fill(x, y, x + a, y + 1, argb);
            s.fill(x, y + 1, x + 1, y + a, argb);
            s.fill(x1 - a, y, x1, y + 1, argb);
            s.fill(x1 - 1, y + 1, x1, y + a, argb);
            s.fill(x, y1 - 1, x + a, y1, argb);
            s.fill(x, y1 - a, x + 1, y1 - 1, argb);
            s.fill(x1 - a, y1 - 1, x1, y1, argb);
            s.fill(x1 - 1, y1 - a, x1, y1 - 1, argb);
        }

        /** A bar: track and fill, or {@code segments} cells with 1 px gaps. */
        public static void bar(FillSink s, int x, int y, int w, int h, float fraction, int segments, int fill,
                               int track) {
            if (w <= 0 || h <= 0) {
                return;
            }
            float f = fraction <= 0f ? 0f : Math.min(1f, fraction);
            if (segments <= 1) {
                s.fill(x, y, x + w, y + h, track);
                int fw = Math.round(w * f);
                if (fw > 0) {
                    s.fill(x, y, x + fw, y + h, fill);
                }
                return;
            }
            int gaps = segments - 1;
            int cell = Math.max(1, (w - gaps) / segments);
            float lit = f * segments;
            for (int i = 0; i < segments; i++) {
                int cx = x + i * (cell + 1);
                if (lit >= i + 1) {
                    s.fill(cx, y, cx + cell, y + h, fill);
                } else if (lit > i) {
                    int part = Math.round(cell * (lit - i));
                    s.fill(cx, y, cx + cell, y + h, track);
                    if (part > 0) {
                        s.fill(cx, y, cx + part, y + h, fill);
                    }
                } else {
                    s.fill(cx, y, cx + cell, y + h, track);
                }
            }
        }

        /**
         * One soft-ribbon quad from (x0, y0) to (x1, y1). The quad is wider than {@code width} by the falloff of the
         * row, and the U coordinate crosses the row from one long edge to the other. Returns the quads written.
         */
        public static int ribbon(VertexSink s, float x0, float y0, float x1, float y1, float width, int argb,
                                 int row) {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1.0e-4f) {
                return 0;
            }
            float half = halfWidth(width, row);
            float nx = -dy / len * half;
            float ny = dx / len * half;
            float v = rowV(row);
            float u0 = 0.5f / ATLAS_W;
            float u1 = 15.5f / ATLAS_W;
            s.vertex(x0 + nx, y0 + ny, u0, v, argb);
            s.vertex(x0 - nx, y0 - ny, u1, v, argb);
            s.vertex(x1 - nx, y1 - ny, u1, v, argb);
            s.vertex(x1 + nx, y1 + ny, u0, v, argb);
            return 1;
        }

        /** Half the quad width that gives a core of {@code width} on a row. */
        static float halfWidth(float width, int row) {
            return switch (row) {
                case ROW_HARD -> width * 0.5f / 0.7f + 0.25f;
                case ROW_SOFT -> width * 0.5f / 0.35f + 0.5f;
                default -> width * 0.5f + 1f;
            };
        }

        /** Number of quads an arc of {@code sweepDeg} is cut into. */
        public static int arcSegments(float sweepDeg) {
            float sweep = Math.abs(sweepDeg);
            if (sweep <= 0f) {
                return 0;
            }
            int n = (int) Math.ceil(sweep / ARC_STEP_DEG);
            return Math.max(1, Math.min(MAX_ARC_SEGMENTS, n));
        }

        /**
         * A circular arc ({@code 0} deg = up, clockwise) as a strip of ring quads whose U runs from the outer to the
         * inner edge. Angles come from a 256-entry table. Returns the quads written.
         */
        public static int arc(VertexSink s, float cx, float cy, float r, float startDeg, float sweepDeg, float width,
                              int argb, int row) {
            int n = arcSegments(sweepDeg);
            if (n == 0 || r <= 0f) {
                return 0;
            }
            float half = halfWidth(width, row);
            float rIn = Math.max(0f, r - half);
            float rOut = r + half;
            float v = rowV(row);
            float u0 = 0.5f / ATLAS_W;
            float u1 = 15.5f / ATLAS_W;
            float step = sweepDeg / n;
            int prev = tableIndex(startDeg);
            for (int i = 1; i <= n; i++) {
                int next = tableIndex(startDeg + step * i);
                float sa = SIN[prev];
                float ca = COS[prev];
                float sb = SIN[next];
                float cb = COS[next];
                s.vertex(cx + sa * rOut, cy - ca * rOut, u0, v, argb);
                s.vertex(cx + sa * rIn, cy - ca * rIn, u1, v, argb);
                s.vertex(cx + sb * rIn, cy - cb * rIn, u1, v, argb);
                s.vertex(cx + sb * rOut, cy - cb * rOut, u0, v, argb);
                prev = next;
            }
            return n;
        }

        static int tableIndex(float deg) {
            return Math.round(deg * (TABLE / 360f)) & (TABLE - 1);
        }

        /** A 7x7 glyph quad from the atlas. */
        public static int glyph(VertexSink s, int glyphIndex, int x, int y, int argb) {
            float u0 = (GLYPH_X + glyphIndex * GLYPH_STEP) / (float) ATLAS_W;
            float u1 = u0 + Glyph.SIZE / (float) ATLAS_W;
            float v0 = GLYPH_Y / (float) ATLAS_H;
            float v1 = v0 + Glyph.SIZE / (float) ATLAS_H;
            int x1 = x + Glyph.SIZE;
            int y1 = y + Glyph.SIZE;
            s.vertex(x, y, u0, v0, argb);
            s.vertex(x, y1, u0, v1, argb);
            s.vertex(x1, y1, u1, v1, argb);
            s.vertex(x1, y, u1, v0, argb);
            return 1;
        }
    }
}
