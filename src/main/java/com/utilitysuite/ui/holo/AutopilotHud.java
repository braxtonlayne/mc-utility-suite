package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.INoticeBoard;
import com.utilitysuite.core.api.visual.NoticeKind;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The autopilot on the HUD (design 8.5). Armed is not a reason to draw; three transients are:
 * <ul>
 *     <li><b>Engage</b> (switched on in game): a 1 px accent line pulses outward from the crosshair to
 *     {@value #PULSE_REACH} px each side and fades over {@value #PULSE_MS} ms, plus one notice {@code AUTOPILOT ONLINE}
 *     ({@code AUTOPILOT OFFLINE} on switch-off, no pulse).</li>
 *     <li><b>Takeover</b> (a reflex acts): screen-corner brackets snap in at {@value #INSET} px inset in {@code SYNC}
 *     (ease-out-back over {@value #SNAP_MS} ms) and stay while the reflex acts. The status line text
 *     ({@link #stemLine}) is set by the HUD module from the same edge.</li>
 *     <li><b>Release</b>: the brackets fade over {@value #RELEASE_MS} ms, then zero draw calls.</li>
 * </ul>
 * Opt-in {@link HoloTheme#armedMarker}: four dim 8 px corner brackets while the autopilot is on and idle.
 * {@link Motion#REDUCED} drops the pulse and the snap (fades stay); {@link Motion#OFF} drops the pulse, shows the
 * brackets at once and removes them at once. Slot {@code FREE}. The state is fed once per client tick by
 * {@link #update}; the drawing reads a clock (milliseconds), allocates nothing and makes no call when idle.
 */
public final class AutopilotHud implements HudElement {

    public static final String ID = "autopilot_hud";
    public static final float PULSE_REACH = 60f;
    public static final float PULSE_GAP = 4f;
    public static final long PULSE_MS = 600L;
    public static final long SNAP_MS = 180L;
    public static final long RELEASE_MS = 400L;
    public static final float INSET = 12f;
    public static final float ARM = 16f;
    public static final float MARKER_ARM = 8f;
    public static final float MARKER_ALPHA = 0.35f;
    public static final String ONLINE = "AUTOPILOT ONLINE";
    public static final String OFFLINE = "AUTOPILOT OFFLINE";

    private static final String[] STEM_LINE = new String[Reflex.values().length];

    static {
        for (Reflex r : Reflex.values()) {
            STEM_LINE[r.ordinal()] = "STEM - " + r.name().replace('_', ' ');
        }
    }

    private final LongSupplier clock;
    private final Supplier<INoticeBoard> notices;

    private boolean seeded;
    private boolean on;
    private Reflex reflex;
    private long pulseAt;
    private long takeoverAt;
    private long releasedAt;

    public AutopilotHud(LongSupplier clock, Supplier<INoticeBoard> notices) {
        this.clock = clock;
        this.notices = notices;
    }

    /** The status line while a reflex acts ("STEM - FALL CLUTCH"); one constant per reflex, null for none. */
    public static String stemLine(Reflex r) {
        return r == null ? null : STEM_LINE[r.ordinal()];
    }

    /**
     * Feeds the autopilot state (client tick). The first call after construction or {@link #reset} only records the
     * state: an autopilot that was already on when the world loaded did not just "engage".
     * @param reflex the reflex acting right now (ignored while off)
     */
    public void update(boolean autopilotOn, Reflex acting) {
        Reflex r = autopilotOn ? acting : null;
        if (!seeded) {
            seeded = true;
            on = autopilotOn;
            reflex = r;
            if (r != null) {
                takeoverAt = clock.getAsLong();
            }
            return;
        }
        long now = clock.getAsLong();
        if (autopilotOn != on) {
            on = autopilotOn;
            if (autopilotOn) {
                pulseAt = now;
                notices.get().post(NoticeKind.INFO, ONLINE, null);
            } else {
                pulseAt = 0L;
                notices.get().post(NoticeKind.INFO, OFFLINE, null);
            }
        }
        if (r != reflex) {
            if (reflex == null) {
                takeoverAt = now;
                releasedAt = 0L;
            } else if (r == null) {
                releasedAt = now;
            }
            reflex = r;
        }
    }

    /** World left: the next update re-seeds. */
    public void reset() {
        seeded = false;
        on = false;
        reflex = null;
        pulseAt = 0L;
        takeoverAt = 0L;
        releasedAt = 0L;
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
        if (reflex != null) {
            return true;
        }
        long now = clock.getAsLong();
        return pulseLive(now) || releaseLive(now) || (on && HoloTheme.armedMarker);
    }

    private boolean pulseLive(long now) {
        return pulseAt != 0L && HoloTheme.motion == Motion.FULL && now - pulseAt < PULSE_MS;
    }

    private boolean releaseLive(long now) {
        return releasedAt != 0L && HoloTheme.motion != Motion.OFF && now - releasedAt < RELEASE_MS;
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        long now = clock.getAsLong();
        float sw = c.screenWidth();
        float sh = c.screenHeight();
        if (pulseLive(now)) {
            float t = (now - pulseAt) / (float) PULSE_MS;
            float half = PULSE_REACH * AnimStore.easeOut(t);
            float a = alpha * (1f - t);
            float cx = sw * 0.5f;
            float cy = (float) Math.floor(sh * 0.5f);
            if (half > PULSE_GAP && a > 0f) {
                c.hLine(cx - half, cx - PULSE_GAP, cy, HoloToken.ACCENT, a);
                c.hLine(cx + PULSE_GAP, cx + half, cy, HoloToken.ACCENT, a);
            }
        }
        if (reflex != null) {
            float inset = INSET;
            float a = alpha;
            if (HoloTheme.motion == Motion.FULL) {
                float t = (now - takeoverAt) / (float) SNAP_MS;
                t = t < 0f ? 0f : Math.min(1f, t);
                inset = INSET + (1f - AnimStore.easeOutBack(t)) * 14f;
                a = alpha * t;
            }
            c.brackets(inset, inset, sw - 2f * inset, sh - 2f * inset, ARM, HoloToken.SYNC, a);
        } else if (releaseLive(now)) {
            float a = alpha * (1f - (now - releasedAt) / (float) RELEASE_MS);
            if (a > 0f) {
                c.brackets(INSET, INSET, sw - 2f * INSET, sh - 2f * INSET, ARM, HoloToken.SYNC, a);
            }
        } else if (on && HoloTheme.armedMarker) {
            c.brackets(INSET, INSET, sw - 2f * INSET, sh - 2f * INSET, MARKER_ARM, HoloToken.ACCENT,
                    alpha * MARKER_ALPHA);
        }
    }
}
