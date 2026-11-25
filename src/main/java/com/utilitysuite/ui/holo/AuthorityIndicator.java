package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.AuthorityState;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.IHudContext;
import com.utilitysuite.core.api.visual.TextRole;

/**
 * Who is steering (design 8.6): a {@link #W}x{@link #H} px split bar {@link #GAP} px under the crosshair. The left
 * share is the user's ({@code MANUAL}), the right share the assist's ({@code ACCENT}, {@code SYNC} during a full
 * takeover). During a hand-back the assist share drains over exactly the remaining hand-back ticks, so control is
 * seen coming back instead of felt dropping. Under the bar the reason in {@code MICRO}; an amber outline flashes while
 * the user overrides ({@code DANGER} when the plan is lost); a small lock while keys are latched.
 * <p>
 * Drawn only while {@link AuthorityState#active()}; fades out over {@link #FADE_SECONDS} after it ends, with the last
 * values. Slot {@code CROSSHAIR}; render thread; allocation free.
 */
public final class AuthorityIndicator implements HudElement {

    public static final String ID = "authority";
    public static final float W = 40f;
    public static final float H = 3f;
    public static final float GAP = 14f;
    public static final float FADE_SECONDS = 0.2f;

    private static final long FADE_KEY = AnimStore.key("authority.fade", 0);
    private static final long SHARE_KEY = AnimStore.key("authority.share", 0);

    private final AuthorityState state;
    private final AnimStore anim;

    private float visibility;
    private float share;
    private boolean full;
    private String reason = "";
    private boolean latched;
    private boolean override;
    private boolean planLost;

    public AuthorityIndicator(AuthorityState state) {
        this(state, AnimStore.SHARED);
    }

    AuthorityIndicator(AuthorityState state, AnimStore anim) {
        this.state = state;
        this.anim = anim;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HudSlot slot() {
        return HudSlot.CROSSHAIR;
    }

    @Override
    public boolean wantsDraw() {
        AuthorityState st = state;
        if (st.active()) {
            anim.set(FADE_KEY, 1f);
            visibility = 1f;
            share = anim.settle(SHARE_KEY, shownAssist(st.assist(), st.handBackLeft(), st.handBackTotal()),
                    AnimStore.TAU_FAST);
            full = st.fullTakeover();
            reason = st.reason().label();
            latched = st.latchedKeys() != 0;
            override = st.overrideTicks() > 0;
            planLost = st.planLost();
            return true;
        }
        visibility = anim.progress(FADE_KEY, false, FADE_SECONDS);
        return visibility > 0f;
    }

    /**
     * The assist share to show. While a hand-back runs it drains linearly with the ticks left (never above the share
     * the publisher still reports, and from a full bar when the publisher already reports 0).
     */
    static float shownAssist(float assist, int handBackLeft, int handBackTotal) {
        float a = assist < 0f ? 0f : Math.min(1f, assist);
        if (handBackLeft > 0 && handBackTotal > 0) {
            float frac = Math.min(1f, handBackLeft / (float) handBackTotal);
            return Math.min(a > 0f ? a : 1f, frac);
        }
        return a;
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        IHudContext ctx = Visuals.contextNow(); // V6: quiet outside combat, pop on combat or fall start
        float a = alpha * visibility * ctx.tacticalAlpha();
        if (a <= 0f) {
            return;
        }
        float w = W * ctx.tacticalScale();
        float bx = x - w * 0.5f;
        float by = y + GAP;
        float s = share < 0f ? 0f : Math.min(1f, share);
        float split = w * (1f - s);
        if (split > 0f) {
            c.plate(bx, by, split, H, HoloToken.MANUAL, a * 0.85f);
        }
        if (split < w) {
            HoloToken t = planLost ? HoloToken.DANGER : full ? HoloToken.SYNC : HoloToken.ACCENT;
            c.plate(bx + split, by, w - split, H, t, a);
        }
        if (override || planLost) {
            float pulse = 0.55f + 0.45f * (float) Math.sin(FrameClock.nowMs() * 0.025);
            HoloToken t = planLost ? HoloToken.DANGER : HoloToken.WARN;
            c.hLine(bx - 1f, bx + w + 1f, by - 1f, t, a * pulse);
            c.hLine(bx - 1f, bx + w + 1f, by + H, t, a * pulse);
        }
        if (latched) {
            float lx = bx + w + 4f;
            float ly = by - 1f;
            c.hLine(lx + 1f, lx + 4f, ly - 3f, HoloToken.MANUAL, a);
            c.vLine(lx + 1f, ly - 3f, ly, HoloToken.MANUAL, a);
            c.vLine(lx + 4f, ly - 3f, ly, HoloToken.MANUAL, a);
            c.plate(lx, ly, 6f, 4f, HoloToken.MANUAL, a);
        }
        String label = reason;
        if (label != null && !label.isEmpty()) {
            float tw = c.width(TextRole.MICRO, label);
            c.text(TextRole.MICRO, label, x - tw * 0.5f, by + H + 2f, HoloToken.TEXT_SECONDARY, a);
        }
    }
}
