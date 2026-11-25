package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.Glyph;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.IHudContext;
import com.utilitysuite.core.api.visual.IWorldMarks;
import com.utilitysuite.core.api.visual.MarkKind;
import com.utilitysuite.core.api.visual.TextRole;

/**
 * The screen-space reticles for everything modules published in {@link IWorldMarks}, drawn from the rectangles the
 * {@link ScreenProjector} computed this frame (design 8.8):
 * <ul>
 *   <li>{@code PRIMARY_TARGET}: four corner brackets hugging the box (at least {@link #MIN_BOX} px) that contract
 *       from {@link #LOCK_SCALE}x on lock-on, a segmented health bar on the left, the attack charge as an arc under
 *       the box with a chevron on the predicted strafe side, and with {@code FLAG_TAG} a leader line to a framed tag
 *       (name, health in mono, distance, state word). {@code FLAG_NO_LOS}: dim and dashed.</li>
 *   <li>{@code THREAT}: a diamond in {@code WARN} ({@code DANGER} with {@code FLAG_LETHAL}); off screen an edge
 *       chevron on an ellipse around the crosshair with the distance.</li>
 *   <li>{@code ALLY}: small corner ticks. {@code LANDING} / {@code OBJECTIVE}: small brackets.</li>
 * </ul>
 * Shows only what modules published (no lookups of its own). Slot {@code FREE}; render thread; allocation free.
 */
public final class ReticleLayer implements HudElement {

    public static final String ID = "reticles";
    /** Smallest side of a target reticle, in canvas pixels. */
    public static final float MIN_BOX = 14f;
    /** Scale the brackets start at when a target is acquired. */
    public static final float LOCK_SCALE = 2.2f;
    public static final float LOCK_SECONDS = 0.18f;
    /** Fully detailed primary reticles per frame (more are drawn as plain brackets, which bounds the budget). */
    public static final int MAX_DETAILED = 2;
    static final int HEALTH_SEGMENTS = 5;
    static final float NO_LOS_DIM = 0.45f;

    private static final String LOCK_KEY = "reticle.lock";
    private static final String PCT = "%";
    private static final String METRES = "m";

    private final ScreenProjector projector;
    private final IWorldMarks marks;
    private final AnimStore anim;
    private final int[] seenGeneration = new int[IWorldMarks.SLOTS];
    private final float[] r = new float[4];
    /** This frame's context-morphing size factor (1 when the module is off). */
    private float popScale = 1f;

    public ReticleLayer(ScreenProjector projector, IWorldMarks marks) {
        this(projector, marks, AnimStore.SHARED);
    }

    ReticleLayer(ScreenProjector projector, IWorldMarks marks, AnimStore anim) {
        this.projector = projector;
        this.marks = marks;
        this.anim = anim;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HudSlot slot() {
        return HudSlot.FREE;
    }

    @Override
    public boolean wantsDraw() {
        IWorldMarks m = marks;
        for (int s = 0; s < IWorldMarks.SLOTS; s++) {
            if (m.occupied(s) && projector.state(s, m.generation(s)) != ScreenProjector.NONE) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        IWorldMarks m = marks;
        IHudContext ctx = Visuals.contextNow(); // V6: quiet outside combat, pop on combat or fall start
        alpha *= ctx.tacticalAlpha();
        popScale = ctx.tacticalScale();
        float sw = c.screenWidth();
        float sh = c.screenHeight();
        int detailed = 0;
        for (int s = 0; s < IWorldMarks.SLOTS; s++) {
            if (!m.occupied(s)) {
                continue;
            }
            int gen = m.generation(s);
            int state = projector.state(s, gen);
            if (state == ScreenProjector.NONE) {
                continue;
            }
            MarkKind kind = m.kind(s);
            int flags = m.flags(s);
            float a = (flags & IWorldMarks.FLAG_NO_LOS) != 0 ? alpha * NO_LOS_DIM : alpha;
            float dist = m.distance(s) >= 0f ? m.distance(s) : projector.value(s, ScreenProjector.DISTANCE);
            if (state != ScreenProjector.ON_SCREEN) {
                if (kind == MarkKind.THREAT) {
                    edgeChevron(c, x, y, sw, sh, projector.value(s, ScreenProjector.BEARING), dist,
                            threatToken(flags), alpha);
                } else if (kind == MarkKind.PRIMARY_TARGET) {
                    edgeChevron(c, x, y, sw, sh, projector.value(s, ScreenProjector.BEARING), dist,
                            HoloToken.ACCENT, a);
                }
                continue;
            }
            r[0] = x + projector.value(s, ScreenProjector.MIN_X) * sw;
            r[1] = y + projector.value(s, ScreenProjector.MIN_Y) * sh;
            r[2] = x + projector.value(s, ScreenProjector.MAX_X) * sw;
            r[3] = y + projector.value(s, ScreenProjector.MAX_Y) * sh;
            switch (kind) {
                case PRIMARY_TARGET -> {
                    if (detailed < MAX_DETAILED) {
                        detailed++;
                        primary(c, m, s, gen, flags, dist, a, x + sw, y);
                    } else {
                        ScreenProjector.expandToMin(r, 0, MIN_BOX);
                        c.brackets(r[0], r[1], r[2] - r[0], r[3] - r[1], arm(), HoloToken.ACCENT, a);
                    }
                }
                case THREAT -> diamond(c, threatToken(flags), alpha);
                case ALLY -> {
                    ScreenProjector.expandToMin(r, 0, 10f);
                    c.brackets(r[0], r[1], r[2] - r[0], r[3] - r[1], 3f, HoloToken.ALLY, a * 0.8f);
                }
                case LANDING, OBJECTIVE -> {
                    ScreenProjector.expandToMin(r, 0, 8f);
                    HoloToken t = kind == MarkKind.LANDING ? HoloToken.ACCENT : HoloToken.CAT_JOBS;
                    c.brackets(r[0], r[1], r[2] - r[0], r[3] - r[1], 3f, t, a);
                    String label = m.label(s);
                    if ((flags & IWorldMarks.FLAG_TAG) != 0 && label != null) {
                        float tw = c.width(TextRole.MICRO, label);
                        c.text(TextRole.MICRO, label, (r[0] + r[2] - tw) * 0.5f, r[3] + 2f, t, a);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static HoloToken threatToken(int flags) {
        return (flags & IWorldMarks.FLAG_LETHAL) != 0 ? HoloToken.DANGER : HoloToken.WARN;
    }

    private float arm() {
        return HoloTheme.bracketArm((int) (r[2] - r[0]), (int) (r[3] - r[1]));
    }

    // ---- primary target ---------------------------------------------------------------------------------------------

    private void primary(IHoloCanvas c, IWorldMarks m, int s, int gen, int flags, float dist, float a, float right,
                         float top) {
        ScreenProjector.expandToMin(r, 0, MIN_BOX);
        float scale = lockScale(s, gen) * popScale;
        if (scale != 1f) {
            float cx = (r[0] + r[2]) * 0.5f;
            float cy = (r[1] + r[3]) * 0.5f;
            float hw = (r[2] - r[0]) * 0.5f * scale;
            float hh = (r[3] - r[1]) * 0.5f * scale;
            r[0] = cx - hw;
            r[2] = cx + hw;
            r[1] = cy - hh;
            r[3] = cy + hh;
        }
        float x0 = r[0];
        float y0 = r[1];
        float x1 = r[2];
        float y1 = r[3];
        float arm = arm();
        if ((flags & IWorldMarks.FLAG_NO_LOS) != 0) {
            dashedBrackets(c, x0, y0, x1, y1, arm, HoloToken.ACCENT, a);
        } else {
            c.brackets(x0, y0, x1 - x0, y1 - y0, arm, HoloToken.ACCENT, a);
        }

        float health = m.health(s);
        if (health >= 0f) {
            healthBar(c, x0 - 5f, y0, y1, Math.min(1f, health), a);
        }

        float charge = m.charge(s);
        float cx = (x0 + x1) * 0.5f;
        if (charge >= 0f) {
            float radius = clamp((x1 - x0) * 0.5f, 8f, 22f);
            float acy = y1 + 5f - radius;
            c.arc(cx, acy, radius, 130f, 100f, 1f, HoloToken.LINE_FAINT, a);
            float ch = Math.min(1f, charge);
            if (ch > 0f) {
                c.arc(cx, acy, radius, 130f, 100f * ch, 1.5f, ch >= 1f ? HoloToken.SYNC : HoloToken.ACCENT, a);
            }
            int side = m.side(s);
            if (side != 0) {
                float ey = acy + radius * 0.643f - Glyph.SIZE * 0.5f;
                if (side > 0) {
                    c.glyph(Glyph.CHEVRON_RIGHT, cx + radius * 0.766f + 2f, ey, HoloToken.ACCENT, a);
                } else {
                    c.glyph(Glyph.CHEVRON_LEFT, cx - radius * 0.766f - 2f - Glyph.SIZE, ey, HoloToken.ACCENT, a);
                }
            }
        }

        String label = m.label(s);
        if ((flags & IWorldMarks.FLAG_TAG) != 0 && label != null) {
            tag(c, label, health, dist, m.stateWord(s), x0, y0, x1, a, right, top);
        }
    }

    /** Lock-on: 2.2x to 1x over {@link #LOCK_SECONDS}, restarted whenever the slot changes generation. */
    private float lockScale(int s, int gen) {
        long key = AnimStore.key(LOCK_KEY, s);
        if (seenGeneration[s] != gen) {
            seenGeneration[s] = gen;
            anim.set(key, 0f);
        }
        float t = anim.progress(key, true, LOCK_SECONDS);
        return LOCK_SCALE + (1f - LOCK_SCALE) * AnimStore.easeOutBack(t);
    }

    private static void dashedBrackets(IHoloCanvas c, float x0, float y0, float x1, float y1, float arm,
                                       HoloToken t, float a) {
        float d = arm * 0.4f;
        float g = arm * 0.6f;
        // top left
        c.hLine(x0, x0 + d, y0, t, a);
        c.hLine(x0 + g, x0 + arm, y0, t, a);
        c.vLine(x0, y0, y0 + d, t, a);
        c.vLine(x0, y0 + g, y0 + arm, t, a);
        // top right
        c.hLine(x1 - d, x1, y0, t, a);
        c.hLine(x1 - arm, x1 - g, y0, t, a);
        c.vLine(x1, y0, y0 + d, t, a);
        c.vLine(x1, y0 + g, y0 + arm, t, a);
        // bottom left
        c.hLine(x0, x0 + d, y1, t, a);
        c.hLine(x0 + g, x0 + arm, y1, t, a);
        c.vLine(x0, y1 - d, y1, t, a);
        c.vLine(x0, y1 - arm, y1 - g, t, a);
        // bottom right
        c.hLine(x1 - d, x1, y1, t, a);
        c.hLine(x1 - arm, x1 - g, y1, t, a);
        c.vLine(x1, y1 - d, y1, t, a);
        c.vLine(x1, y1 - arm, y1 - g, t, a);
    }

    /** Vertical segmented bar, filled from the bottom; coloured by {@link HoloTheme#healthToken}. */
    private static void healthBar(IHoloCanvas c, float x, float y0, float y1, float f, float a) {
        float h = y1 - y0;
        c.plate(x, y0, 2f, h, HoloToken.LINE_FAINT, a);
        float seg = (h - (HEALTH_SEGMENTS - 1)) / HEALTH_SEGMENTS;
        if (seg <= 0f || f <= 0f) {
            return;
        }
        HoloToken t = HoloTheme.healthToken(f);
        for (int i = 0; i < HEALTH_SEGMENTS; i++) {
            float part = f * HEALTH_SEGMENTS - i;
            if (part <= 0f) {
                break;
            }
            if (part > 1f) {
                part = 1f;
            }
            float bottom = y1 - i * (seg + 1f);
            c.plate(x, bottom - seg * part, 2f, seg * part, t, a);
        }
    }

    private static void tag(IHoloCanvas c, String label, float health, float dist, String word, float x0, float y0,
                            float x1, float a, float right, float top) {
        int hp = health >= 0f ? Math.round(Math.min(1f, health) * 100f) : -1;
        int d = dist >= 0f ? (int) (dist + 0.5f) : -1;
        float w1 = c.width(TextRole.UI, label);
        float w2 = 0f;
        if (hp >= 0) {
            w2 += c.numberWidth(TextRole.MONO, hp) + c.width(TextRole.MONO, PCT) + 4f;
        }
        if (d >= 0) {
            w2 += c.numberWidth(TextRole.MONO, d) + c.width(TextRole.MONO, METRES) + 4f;
        }
        if (word != null) {
            w2 += c.width(TextRole.MICRO, word);
        }
        float lh = c.lineHeight(TextRole.UI);
        float tagW = Math.max(w1, w2) + 8f;
        float tagH = lh * 2f + 7f;
        float tx = x1 + 12f;
        boolean left = tx + tagW > right - 2f;
        if (left) {
            tx = x0 - 12f - tagW;
        }
        float ty = Math.max(top + 2f, y0 - 10f);
        c.ribbon(left ? x0 - 1f : x1 + 1f, y0 + 1f, left ? tx + tagW : tx, ty + tagH * 0.5f, 1f,
                HoloToken.LINE_STRONG, a);
        c.frame(tx, ty, tagW, tagH, FrameLevel.FRAMED, false, a);
        float px = tx + 4f;
        c.text(TextRole.UI, label, px, ty + 3f, HoloToken.TEXT, a);
        float ry = ty + 4f + lh;
        if (hp >= 0) {
            HoloToken ht = HoloTheme.healthToken(health);
            c.number(TextRole.MONO, hp, px, ry, ht, a);
            px += c.numberWidth(TextRole.MONO, hp);
            c.text(TextRole.MONO, PCT, px, ry, ht, a);
            px += c.width(TextRole.MONO, PCT) + 4f;
        }
        if (d >= 0) {
            c.number(TextRole.MONO, d, px, ry, HoloToken.TEXT_SECONDARY, a);
            px += c.numberWidth(TextRole.MONO, d);
            c.text(TextRole.MONO, METRES, px, ry, HoloToken.TEXT_SECONDARY, a);
            px += c.width(TextRole.MONO, METRES) + 4f;
        }
        if (word != null) {
            c.text(TextRole.MICRO, word, px, ry + 1f, HoloToken.ACCENT, a);
        }
    }

    // ---- threats and edge chevrons ----------------------------------------------------------------------------------

    private void diamond(IHoloCanvas c, HoloToken t, float a) {
        float cx = (r[0] + r[2]) * 0.5f;
        float cy = (r[1] + r[3]) * 0.5f;
        float s = clamp(Math.max(r[2] - r[0], r[3] - r[1]) * 0.5f + 3f, 7f, 18f);
        c.ribbon(cx, cy - s, cx + s, cy, 1f, t, a);
        c.ribbon(cx + s, cy, cx, cy + s, 1f, t, a);
        c.ribbon(cx, cy + s, cx - s, cy, 1f, t, a);
        c.ribbon(cx - s, cy, cx, cy - s, 1f, t, a);
    }

    /** A chevron on an ellipse around the crosshair pointing along {@code bearingDeg} (0 = up, clockwise). */
    static void edgeChevron(IHoloCanvas c, float ox, float oy, float sw, float sh, float bearingDeg, float dist,
                            HoloToken t, float a) {
        double b = Math.toRadians(bearingDeg);
        float dx = (float) Math.sin(b);
        float dy = (float) -Math.cos(b);
        float rx = Math.min(sw * 0.32f, 150f);
        float ry = Math.min(sh * 0.32f, 90f);
        float px = ox + sw * 0.5f + dx * rx;
        float py = oy + sh * 0.5f + dy * ry;
        float tipX = px + dx * 4f;
        float tipY = py + dy * 4f;
        float bx = px - dx * 2f;
        float by = py - dy * 2f;
        c.ribbon(bx - dy * 4f, by + dx * 4f, tipX, tipY, 1.5f, t, a);
        c.ribbon(bx + dy * 4f, by - dx * 4f, tipX, tipY, 1.5f, t, a);
        if (dist >= 0f) {
            int d = (int) (dist + 0.5f);
            float nw = c.numberWidth(TextRole.MICRO, d);
            c.number(TextRole.MICRO, d, px - dx * 11f - nw * 0.5f, py - dy * 11f - 3.5f, t, a);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
