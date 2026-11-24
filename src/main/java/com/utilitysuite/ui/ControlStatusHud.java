package com.utilitysuite.ui;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IArbiterHistory;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IModuleRegistry;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.TextRole;

/**
 * The opt-in "why did it stop?" control status (off by default; the setting lives on {@code suite_hud}). A quiet glass
 * plate in {@link HudSlot#RIGHT_MID} with up to two rows:
 * <ol>
 *   <li><b>now</b>: who holds which channels ({@code CTRL combat_assistant move+cam}) and the active phase of Movement
 *       Assist, the Combat Assistant and the travel (elytra) autopilot ({@code MOVE TAKEOFF}, {@code COMBAT ENGAGED},
 *       {@code TRAVEL ELYTRA_CRUISE});</li>
 *   <li><b>last stop</b>: the arbiter's last preemption, manual override, denial, timeout or Stop-All
 *       ({@link IArbiterHistory#lastStop}) with its owner, channels, who caused it and how long ago, for
 *       {@value #SHOW_STOP_TICKS} ticks after it happened.</li>
 * </ol>
 * Reads only contracts: {@link IActionArbiter#holderOrNull}, {@link IActionArbiter#history} and {@link IModule#phase}
 * looked up by module id. Every text run is a string someone already holds (module ids, enum names, the pre-built
 * channel labels below, constant captions) and the age is drawn with {@link IHoloCanvas#number}, so a frame builds no
 * text and allocates nothing. Idle (no lease, no phase, no recent stop) draws nothing; the host hides it in
 * Clean-Screen. Pure Java.
 */
public final class ControlStatusHud implements HudElement {

    public static final String ID = "control_status";
    /** How long the last stop stays on the plate after it happened (10 s). */
    public static final int SHOW_STOP_TICKS = 200;
    /** Module ids read for phases (their {@code ID} constants; strings so the Shell stays on the contracts). */
    static final String MOVEMENT_ID = "movement_assist";
    static final String COMBAT_ID = "combat_assistant";
    static final String TRAVEL_ID = "travel_autopilot";

    private static final int MAX_LEASES = 4;
    private static final float PAD = 5f;
    private static final float SEG_GAP = 4f;
    private static final float GROUP_GAP = 10f;
    private static final float ROW_GAP = 2f;

    static final String CAP_CTRL = "CTRL";
    static final String CAP_MOVE = "MOVE";
    static final String CAP_COMBAT = "COMBAT";
    static final String CAP_TRAVEL = "TRAVEL";
    static final String CAP_LAST = "LAST";
    static final String WORD_BY = "by";
    static final String WORD_AGO = "s ago";

    /** Channel-mask labels, built once: index = mask of {@code 1 << ControlChannel.ordinal()} bits. */
    private static final String[] CHANNEL_LABELS = buildChannelLabels();

    private final boolean lazy;
    private IActionArbiter arbiter;
    private IModule movement;
    private IModule combat;
    private IModule travel;
    private boolean resolved;

    /** Written by {@code SuiteHud.read} on the client thread. */
    boolean enabled;

    // gathered by wantsDraw (one frame), read by width/draw
    private final ControlLease[] leases = new ControlLease[MAX_LEASES];
    private final int[] leaseMasks = new int[MAX_LEASES];
    private int leaseCount;
    private String movePhase;
    private String combatPhase;
    private String travelPhase;
    private boolean showStop;
    private String stopReason;
    private HoloToken stopToken;
    private String stopOwner;
    private String stopChannels;
    private String stopBy;
    private int stopSeconds;

    /** In game: the arbiter and modules come from {@link Services} on first use. */
    public ControlStatusHud() {
        this.lazy = true;
    }

    /** Tests: fixed collaborators (any module may be null). */
    ControlStatusHud(IActionArbiter arbiter, IModule movement, IModule combat, IModule travel) {
        this.lazy = false;
        this.arbiter = arbiter;
        this.movement = movement;
        this.combat = combat;
        this.travel = travel;
        this.resolved = true;
    }

    private static String[] buildChannelLabels() {
        ControlChannel[] all = ControlChannel.all();
        String[] out = new String[1 << all.length];
        for (int m = 0; m < out.length; m++) {
            StringBuilder sb = new StringBuilder();
            for (ControlChannel ch : all) {
                if ((m & (1 << ch.ordinal())) != 0) {
                    if (!sb.isEmpty()) {
                        sb.append('+');
                    }
                    sb.append(shortName(ch));
                }
            }
            out[m] = sb.isEmpty() ? "-" : sb.toString();
        }
        return out;
    }

    private static String shortName(ControlChannel ch) {
        return switch (ch) {
            case MOVEMENT -> "move";
            case CAMERA -> "cam";
            case HOTBAR -> "hotbar";
            case WORLD_INTERACT -> "use";
        };
    }

    /** Pre-built label of a channel mask ("move+cam"); "-" for an empty or unknown mask. */
    static String channelLabel(int mask) {
        return mask > 0 && mask < CHANNEL_LABELS.length ? CHANNEL_LABELS[mask] : "-";
    }

    /** Looks the collaborators up once (Optional lookups happen here, never per frame afterwards). */
    private void resolve() {
        if (resolved || !lazy) {
            return;
        }
        resolved = true;
        arbiter = Services.arbiter();
        IModuleRegistry reg = Services.modules();
        movement = reg.byId(MOVEMENT_ID).orElse(null);
        combat = reg.byId(COMBAT_ID).orElse(null);
        travel = reg.byId(TRAVEL_ID).orElse(null);
    }

    // ---- element ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HudSlot slot() {
        return HudSlot.RIGHT_MID;
    }

    @Override
    public boolean wantsDraw() {
        if (!enabled) {
            return false;
        }
        resolve();
        IActionArbiter a = arbiter;
        if (a == null) {
            return false;
        }
        gatherLeases(a);
        movePhase = movement == null ? null : movement.phase();
        combatPhase = combat == null ? null : combat.phase();
        travelPhase = travel == null ? null : travel.phase();
        gatherStop(a.history());
        return leaseCount > 0 || movePhase != null || combatPhase != null || travelPhase != null || showStop;
    }

    private void gatherLeases(IActionArbiter a) {
        for (int i = 0; i < leaseCount; i++) {
            leases[i] = null;
        }
        int n = 0;
        ControlChannel[] all = ControlChannel.all();
        for (int c = 0; c < all.length; c++) {
            ControlLease h = a.holderOrNull(all[c]);
            if (h == null) {
                continue;
            }
            int k = 0;
            while (k < n && leases[k] != h) {
                k++;
            }
            if (k == n) {
                if (n == MAX_LEASES) {
                    continue;
                }
                leases[n] = h;
                leaseMasks[n] = 0;
                n++;
            }
            leaseMasks[k] |= 1 << c;
        }
        leaseCount = n;
    }

    private void gatherStop(IArbiterHistory history) {
        IArbiterHistory s = history.lastStop();
        long age = s.size() == 0 ? Long.MAX_VALUE : history.now() - s.tick(0);
        showStop = age >= 0 && age <= SHOW_STOP_TICKS;
        if (!showStop) {
            return;
        }
        stopReason = s.reasonLabel(0);
        LeaseEndReason r = s.endReason(0);
        stopToken = r == LeaseEndReason.MANUAL_OVERRIDE ? HoloToken.MANUAL
                : r == LeaseEndReason.STOP_ALL ? HoloToken.DANGER : HoloToken.WARN;
        stopOwner = s.owner(0);
        stopChannels = channelLabel(s.channelMask(0));
        String by = s.by(0);
        stopBy = by == null || by.isEmpty() || by.equals(stopOwner) ? null : by;
        stopSeconds = (int) (age / 20L);
    }

    private boolean hasNowRow() {
        return leaseCount > 0 || movePhase != null || combatPhase != null || travelPhase != null;
    }

    @Override
    public float width(IHoloCanvas c) {
        float w = 0f;
        if (hasNowRow()) {
            w = nowRow(c, 0f, 0f, 0f, false);
        }
        if (showStop) {
            w = Math.max(w, stopRow(c, 0f, 0f, 0f, false));
        }
        return w <= 0f ? 0f : w + 2f * PAD;
    }

    @Override
    public float height(IHoloCanvas c) {
        int rows = (hasNowRow() ? 1 : 0) + (showStop ? 1 : 0);
        if (rows == 0) {
            return 0f;
        }
        return 2f * PAD + rows * c.lineHeight(TextRole.MICRO) + (rows - 1) * ROW_GAP;
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        float w = width(c);
        if (w <= 0f || alpha <= 0f) {
            return;
        }
        c.frame(x, y, w, height(c), FrameLevel.QUIET, false, alpha);
        float tx = x + PAD;
        float ty = y + PAD;
        if (hasNowRow()) {
            nowRow(c, tx, ty, alpha, true);
            ty += c.lineHeight(TextRole.MICRO) + ROW_GAP;
        }
        if (showStop) {
            stopRow(c, tx, ty, alpha, true);
        }
    }

    /** Lays out (and with {@code draw}, draws) the "now" row; returns its width. */
    private float nowRow(IHoloCanvas c, float x0, float y, float a, boolean draw) {
        float x = x0;
        boolean first = true;
        if (leaseCount > 0) {
            x = run(c, draw, x, y, CAP_CTRL, HoloToken.TEXT_MUTED, a);
            for (int i = 0; i < leaseCount; i++) {
                x = run(c, draw, x + SEG_GAP, y, leases[i].moduleId(), HoloToken.TEXT, a);
                x = run(c, draw, x + SEG_GAP, y, channelLabel(leaseMasks[i]), HoloToken.TEXT_SECONDARY, a);
            }
            first = false;
        }
        x = phase(c, draw, x, y, first, CAP_MOVE, movePhase, a);
        first &= movePhase == null;
        x = phase(c, draw, x, y, first, CAP_COMBAT, combatPhase, a);
        first &= combatPhase == null;
        x = phase(c, draw, x, y, first, CAP_TRAVEL, travelPhase, a);
        return x - x0;
    }

    private float phase(IHoloCanvas c, boolean draw, float x, float y, boolean first, String cap, String value,
                        float a) {
        if (value == null) {
            return x;
        }
        x = run(c, draw, first ? x : x + GROUP_GAP, y, cap, HoloToken.TEXT_MUTED, a);
        return run(c, draw, x + SEG_GAP, y, value, HoloToken.ACCENT, a);
    }

    /** Lays out (and with {@code draw}, draws) the "last stop" row; returns its width. */
    private float stopRow(IHoloCanvas c, float x0, float y, float a, boolean draw) {
        float x = run(c, draw, x0, y, CAP_LAST, HoloToken.TEXT_MUTED, a);
        x = run(c, draw, x + SEG_GAP, y, stopReason, stopToken, a);
        x = run(c, draw, x + SEG_GAP, y, stopOwner, HoloToken.TEXT, a);
        x = run(c, draw, x + SEG_GAP, y, stopChannels, HoloToken.TEXT_SECONDARY, a);
        if (stopBy != null) {
            x = run(c, draw, x + SEG_GAP, y, WORD_BY, HoloToken.TEXT_MUTED, a);
            x = run(c, draw, x + SEG_GAP, y, stopBy, HoloToken.TEXT, a);
        }
        x += GROUP_GAP;
        if (draw) {
            c.number(TextRole.MONO, stopSeconds, x, y, HoloToken.TEXT_SECONDARY, a);
        }
        x += c.numberWidth(TextRole.MONO, stopSeconds);
        return run(c, draw, x, y, WORD_AGO, HoloToken.TEXT_MUTED, a) - x0;
    }

    private static float run(IHoloCanvas c, boolean draw, float x, float y, String s, HoloToken t, float a) {
        if (draw) {
            c.text(TextRole.MICRO, s, x, y, t, a);
        }
        return x + c.width(TextRole.MICRO, s);
    }

    // ---- tests ------------------------------------------------------------------------------------------------------

    int leaseCount() {
        return leaseCount;
    }

    boolean showsStop() {
        return showStop;
    }

    String stopReason() {
        return stopReason;
    }

    String stopBy() {
        return stopBy;
    }
}
