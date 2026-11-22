package com.utilitysuite.ui;

import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.Glyph;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.TextRole;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.FrameClock;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.holo.Motion;

import java.util.List;

/**
 * The enabled-modules list (opt-in, top-right) as a {@link HudElement}: right-aligned rows on glass plates, no text
 * shadow, a 2 px category-hue bar on the screen edge, longest name first, the autopilot heading the list while it is
 * on. A module holding a control lease right now shows the acting glyph in the sync hue (driven by the
 * {@code LeaseGranted} / {@code LeaseEnded} events, no polling).
 * <p>
 * Motion: a row turning on slides in {@value #SLIDE} px from the right and fades in over {@value #FADE_S} s; a row
 * turning off fades out where it is while its neighbours close the gap (each row's y settles toward its slot). The
 * per-row state lives in preallocated arrays indexed by the module's registry index, so a row keeps its animation
 * through re-sorting. The set of enabled modules is checked once a second (a bit mask compare); names, widths and
 * the order are rebuilt only when it changed. {@link #draw} allocates nothing. Pure Java.
 */
public final class ModuleListHud implements HudElement {

    public static final String ID = "module_list";
    /** Registry indices covered (the enabled mask has room for 63 modules plus the autopilot bit). */
    static final int MAX = 63;
    static final int AUTOPILOT = MAX;
    static final int ROWS = MAX + 1;
    static final int ROW_H = 11;
    static final float FADE_S = 0.16f;
    static final float SLIDE = 10f;
    private static final int BAR = HoloTheme.BAR;
    private static final int PAD_L = 4;
    private static final int PAD_R = 3;
    private static final int GLYPH_ROOM = Glyph.SIZE + 3;
    private static final String AUTOPILOT_NAME = "Autopilot";

    boolean enabled;
    /** F3 open: written each frame by the HUD module. */
    boolean blocked;

    private final String[] ids = new String[ROWS];
    private final String[] names = new String[ROWS];
    private final HoloToken[] tokens = new HoloToken[ROWS];
    private final boolean[] on = new boolean[ROWS];
    private final float[] textW = new float[ROWS];
    private final float[] alpha = new float[ROWS];
    private final float[] y = new float[ROWS];
    private final float[] targetY = new float[ROWS];
    private final int[] leases = new int[ROWS];
    private final int[] order = new int[ROWS];
    private int count;
    private boolean dirty;
    private boolean animating;
    private boolean autopilotActing;
    private float maxW;
    private long lastMask = -1L;
    private int ticks;

    /** Subscribes to the lease events (start-up, once). */
    void subscribe() {
        Services.events().subscribe(com.utilitysuite.core.api.SuiteEvent.LeaseGranted.class,
                e -> leaseGranted(e.moduleId()));
        Services.events().subscribe(com.utilitysuite.core.api.SuiteEvent.LeaseEnded.class,
                e -> leaseEnded(e.moduleId()));
    }

    /** Called from the HUD module's tick; cheap early-out when off. */
    void tick() {
        if (!enabled) {
            return;
        }
        IAutopilot ap = Services.autopilotOrNull();
        autopilotActing = ap != null && ap.isOn() && ap.activeReflex() != null;
        if (++ticks % 20 != 0) {
            return;
        }
        apply(Services.modules().all(), ap != null && ap.isOn());
    }

    void invalidate() {
        lastMask = -1L;
        ticks = 19; // rebuild on the next tick
    }

    /** Applies the registry state when the enabled set changed (also the test seam). */
    public void apply(List<IModule> all, boolean autopilotOn) {
        int n = Math.min(all.size(), MAX);
        long mask = autopilotOn ? 1L << AUTOPILOT : 0L;
        for (int i = 0; i < n; i++) {
            if (all.get(i).isEnabled()) {
                mask |= 1L << i;
            }
        }
        if (mask == lastMask) {
            return;
        }
        lastMask = mask;
        for (int i = 0; i < MAX; i++) {
            if (i >= n) {
                on[i] = false;
                continue;
            }
            IModule m = all.get(i);
            ids[i] = m.id();
            boolean show = m.isEnabled() && m.category() != ModuleCategory.CORE;
            if (show) {
                String name = m instanceof IConfigurable c ? c.displayName() : m.id();
                if (!name.equals(names[i])) {
                    names[i] = name;
                    textW[i] = -1f;
                }
                tokens[i] = tokenOf(m.category());
            }
            on[i] = show;
        }
        names[AUTOPILOT] = AUTOPILOT_NAME;
        ids[AUTOPILOT] = null;
        tokens[AUTOPILOT] = HoloToken.ACCENT;
        on[AUTOPILOT] = autopilotOn;
        dirty = true;
    }

    /** Category hue token of a module (2 px bar). */
    static HoloToken tokenOf(ModuleCategory c) {
        return switch (c) {
            case JOBS -> HoloToken.CAT_JOBS;
            case WORLD -> HoloToken.CAT_WORLD;
            case TRAVEL -> HoloToken.CAT_TRAVEL;
            case COMBAT -> HoloToken.CAT_COMBAT;
            case TOOLS -> HoloToken.CAT_TOOLS;
            case CORE -> HoloToken.TEXT_MUTED;
        };
    }

    public void leaseGranted(String moduleId) {
        int i = indexOf(moduleId);
        if (i >= 0) {
            leases[i]++;
        }
    }

    public void leaseEnded(String moduleId) {
        int i = indexOf(moduleId);
        if (i >= 0 && leases[i] > 0) {
            leases[i]--;
        }
    }

    private int indexOf(String moduleId) {
        if (moduleId == null) {
            return -1;
        }
        for (int i = 0; i < MAX; i++) {
            if (moduleId.equals(ids[i])) {
                return i;
            }
        }
        return -1;
    }

    /** True while the module at this registry index holds a control lease (tests). */
    boolean acting(int index) {
        return index == AUTOPILOT ? autopilotActing : leases[index] > 0;
    }

    /** Rows currently on (tests). */
    int count() {
        return count;
    }

    // ---- element ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HudSlot slot() {
        return HudSlot.TOP_RIGHT;
    }

    @Override
    public boolean wantsDraw() {
        return enabled && !blocked && (dirty || count > 0 || animating);
    }

    @Override
    public float width(IHoloCanvas c) {
        layout(c);
        return count == 0 ? 0f : maxW + PAD_L + PAD_R + GLYPH_ROOM + BAR;
    }

    @Override
    public float height(IHoloCanvas c) {
        layout(c);
        return count * ROW_H;
    }

    /** Measures new names and re-sorts when the enabled set changed (not per frame). */
    void layout(IHoloCanvas c) {
        if (!dirty) {
            return;
        }
        dirty = false;
        int n = 0;
        if (on[AUTOPILOT]) {
            order[n++] = AUTOPILOT;
        }
        int firstSorted = n;
        float widest = 0f;
        for (int i = 0; i < ROWS; i++) {
            if (names[i] != null && textW[i] < 0f) {
                textW[i] = c.width(TextRole.UI, names[i]);
            }
            if (i == AUTOPILOT || !on[i]) {
                continue;
            }
            // longest first (insertion sort over a few dozen entries, only on change)
            int j = n;
            while (j > firstSorted && textW[order[j - 1]] < textW[i]) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = i;
            n++;
        }
        for (int k = 0; k < n; k++) {
            int i = order[k];
            widest = Math.max(widest, textW[i]);
            targetY[i] = k * ROW_H;
            if (alpha[i] <= 0f) {
                y[i] = targetY[i]; // entering: no vertical travel, it slides in from the side
            }
        }
        count = n;
        maxW = widest;
    }

    @Override
    public void draw(IHoloCanvas c, float x, float top, float a) {
        layout(c);
        float dt = FrameClock.dtSeconds();
        Motion motion = HoloTheme.motion;
        float fadeStep = dt / FADE_S;
        float follow = 1f - (float) Math.exp(-dt / AnimStore.TAU_NORMAL);
        float right = c.screenWidth();
        boolean moving = false;
        for (int i = 0; i < ROWS; i++) {
            if (names[i] == null) {
                continue;
            }
            boolean rowOn = on[i];
            float al = alpha[i];
            if (!rowOn && al <= 0f) {
                continue;
            }
            if (motion == Motion.OFF) {
                al = rowOn ? 1f : 0f;
                y[i] = targetY[i];
            } else {
                al = rowOn ? Math.min(1f, al + fadeStep) : Math.max(0f, al - fadeStep);
                if (rowOn) {
                    float d = targetY[i] - y[i];
                    if (d > 0.05f || d < -0.05f) {
                        y[i] += d * follow;
                        moving = true;
                    } else {
                        y[i] = targetY[i];
                    }
                }
                if (rowOn ? al < 1f : al > 0f) {
                    moving = true;
                }
            }
            alpha[i] = al;
            if (al <= 0f) {
                continue;
            }
            float ra = a * AnimStore.easeOut(al);
            float slide = motion == Motion.FULL ? (1f - al) * SLIDE : 0f;
            boolean act = acting(i);
            float rowY = top + Math.round(y[i]);
            float textX = right - BAR - PAD_R - textW[i] + slide;
            float plateX = textX - PAD_L - (act ? GLYPH_ROOM : 0);
            c.plate(plateX, rowY, right - BAR - plateX, ROW_H, HoloToken.GLASS_HUD, ra);
            c.plate(right - BAR, rowY, BAR, ROW_H, act ? HoloToken.SYNC : tokens[i], ra);
            if (act) {
                c.glyph(Glyph.ACTING, textX - GLYPH_ROOM, rowY + 2, HoloToken.SYNC, ra);
            }
            c.text(TextRole.UI, names[i], textX, rowY + 2, i == AUTOPILOT ? HoloToken.ACCENT : HoloToken.TEXT, ra);
        }
        animating = moving;
    }
}
