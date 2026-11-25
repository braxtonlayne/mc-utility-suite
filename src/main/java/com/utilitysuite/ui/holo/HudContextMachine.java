package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.HudContextState;
import com.utilitysuite.core.api.visual.IHudContext;

/**
 * The engine-free state machine behind the context-morphing HUD (V6). {@link #tick} (client tick, 20/s) takes what
 * the player is doing and picks a {@link HudContextState} by priority: FALLING, COMBAT (held {@link #combatHoldTicks}
 * after the last combat signal), BUILDING (held {@link #BUILD_HOLD_TICKS}), IDLE_VIEW (still for
 * {@link #idleTicks}), EXPLORING. {@link #advance} (each rendered frame) eases the outputs toward the state:
 * <ul>
 *     <li>{@link #hudAlpha}: 1, fading slowly to {@link #idleAlpha} in IDLE_VIEW and quickly back on any input;</li>
 *     <li>{@link #tacticalAlpha}: {@link #quietAlpha} outside COMBAT/FALLING, eased fast to 1 inside;</li>
 *     <li>{@link #tacticalScale}: a {@link #POP_SECONDS} pop from {@link #POP_SCALE} to 1 when COMBAT or FALLING
 *     starts (when {@link #pop} is on).</li>
 * </ul>
 * Disabled, every output is 1 and the state EXPLORING, so the HUD looks exactly as without the module. Fields only,
 * no allocation.
 */
public final class HudContextMachine implements IHudContext {

    public static final int BUILD_HOLD_TICKS = 40;
    public static final float POP_SCALE = 1.25f;
    public static final float POP_SECONDS = 0.25f;
    /** Easing time constants (seconds): emphasis in/out, idle fade out, idle fade back in. */
    public static final float TAU_EMPHASIS = 0.06f;
    public static final float TAU_IDLE_OUT = 0.6f;
    public static final float TAU_IDLE_IN = 0.08f;

    // settings (written by the module)
    public boolean enabled;
    public int idleTicks = 160;
    public int combatHoldTicks = 60;
    public float idleAlpha = 0.35f;
    public float quietAlpha = 0.6f;
    public boolean pop = true;

    private HudContextState state = HudContextState.EXPLORING;
    private int stillFor;
    private int combatLeft;
    private int buildLeft;
    private float emphasis;
    private float idle;
    private float popT = 1f;

    /** One client tick of input. {@code still}: no movement and no look change since the last tick. */
    public void tick(boolean combat, boolean falling, boolean building, boolean still) {
        if (!enabled) {
            reset();
            return;
        }
        combatLeft = combat ? combatHoldTicks : Math.max(0, combatLeft - 1);
        buildLeft = building ? BUILD_HOLD_TICKS : Math.max(0, buildLeft - 1);
        stillFor = still ? stillFor + 1 : 0;
        HudContextState next;
        if (falling) {
            next = HudContextState.FALLING;
        } else if (combatLeft > 0) {
            next = HudContextState.COMBAT;
        } else if (buildLeft > 0) {
            next = HudContextState.BUILDING;
        } else if (stillFor >= idleTicks) {
            next = HudContextState.IDLE_VIEW;
        } else {
            next = HudContextState.EXPLORING;
        }
        if (emphasised(next) && !emphasised(state)) {
            popT = pop ? 0f : 1f;
        }
        state = next;
    }

    /** Eases the outputs by {@code dt} seconds (a rendered frame). */
    public void advance(float dt) {
        if (!enabled || dt <= 0f) {
            return;
        }
        emphasis = ease(emphasis, emphasised(state) ? 1f : 0f, dt, TAU_EMPHASIS);
        boolean idleNow = state == HudContextState.IDLE_VIEW;
        idle = ease(idle, idleNow ? 1f : 0f, dt, idleNow ? TAU_IDLE_OUT : TAU_IDLE_IN);
        if (popT < 1f) {
            popT = Math.min(1f, popT + dt / POP_SECONDS);
        }
    }

    public void reset() {
        state = HudContextState.EXPLORING;
        stillFor = 0;
        combatLeft = 0;
        buildLeft = 0;
        emphasis = 0f;
        idle = 0f;
        popT = 1f;
    }

    static boolean emphasised(HudContextState s) {
        return s == HudContextState.COMBAT || s == HudContextState.FALLING;
    }

    /** Exponential approach of {@code value} to {@code target} with time constant {@code tau}; snaps when close. */
    static float ease(float value, float target, float dt, float tau) {
        float k = 1f - (float) Math.exp(-dt / tau);
        float v = value + (target - value) * k;
        return Math.abs(target - v) < 1.0e-3f ? target : v;
    }

    @Override
    public HudContextState state() {
        return enabled ? state : HudContextState.EXPLORING;
    }

    @Override
    public float hudAlpha() {
        return enabled ? 1f - idle * (1f - idleAlpha) : 1f;
    }

    @Override
    public float tacticalAlpha() {
        return enabled ? quietAlpha + (1f - quietAlpha) * emphasis : 1f;
    }

    @Override
    public float tacticalScale() {
        if (!enabled || popT >= 1f) {
            return 1f;
        }
        return 1f + (POP_SCALE - 1f) * (1f - AnimStore.easeOut(popT));
    }
}
