package com.utilitysuite.core.api.visual;

/**
 * Who is steering right now: the share of movement control an assist holds, and how much of a hand-back is left.
 * Written once per tick by the module that holds the control (constant module id), read every frame by the HUD,
 * which shows the split and drains it over the hand-back so control visibly returns instead of dropping.
 * Primitives only, client thread only, allocation free.
 */
public final class AuthorityState {

    /** Shared by every module and the Shell, with or without a registered {@link IVisuals}. */
    public static final AuthorityState SHARED = new AuthorityState();

    private String owner;
    private float assist;
    private boolean full;
    private AuthorityReason reason = AuthorityReason.NONE;
    private int handBackLeft;
    private int handBackTotal;
    private int latchedKeys;
    private int overrideTicks;
    private boolean planLost;

    /**
     * Publishes the state of this tick. A different owner only replaces the current one when it holds more authority.
     *
     * @param assist01      0 = the user steers alone, 1 = the assist drives every axis
     * @param fullTakeover  a reflex or takeover mode drives (drawn in the acting hue)
     * @param handBackLeft  ticks left of a running hand-back, 0 when none
     * @param handBackTotal length of that hand-back in ticks
     * @param latchedKeys   bit mask by {@code SimKey.ordinal()} of keys held back until released and pressed again
     */
    public void publish(String ownerId, float assist01, boolean fullTakeover, AuthorityReason why, int handBackLeft,
                        int handBackTotal, int latchedKeys) {
        if (owner != null && !owner.equals(ownerId) && assist > assist01) {
            return;
        }
        owner = ownerId;
        assist = assist01 < 0f ? 0f : Math.min(1f, assist01);
        full = fullTakeover;
        reason = why;
        this.handBackLeft = handBackLeft;
        this.handBackTotal = handBackTotal;
        this.latchedKeys = latchedKeys;
    }

    /** The user took an axis or everything back: the HUD flashes for {@code ticks}. */
    public void noteOverride(int ticks, boolean planIsLost) {
        overrideTicks = ticks;
        planLost = planIsLost;
    }

    /** The owner has nothing to show any more. Ignored when another module owns the state. */
    public void clear(String ownerId) {
        if (owner != null && owner.equals(ownerId)) {
            owner = null;
            assist = 0f;
            full = false;
            reason = AuthorityReason.NONE;
            handBackLeft = 0;
            handBackTotal = 0;
            latchedKeys = 0;
        }
    }

    public void reset() {
        owner = null;
        assist = 0f;
        full = false;
        reason = AuthorityReason.NONE;
        handBackLeft = 0;
        handBackTotal = 0;
        latchedKeys = 0;
        overrideTicks = 0;
        planLost = false;
    }

    /** Once per client tick by the Shell: counts the override flash down. */
    public void tick() {
        if (overrideTicks > 0 && --overrideTicks == 0) {
            planLost = false;
        }
    }

    /** True when there is anything to draw. */
    public boolean active() {
        return overrideTicks > 0 || owner != null && (assist > 0f || handBackLeft > 0 || latchedKeys != 0);
    }

    public float assist() {
        return assist;
    }

    public boolean fullTakeover() {
        return full;
    }

    public AuthorityReason reason() {
        return reason;
    }

    public int handBackLeft() {
        return handBackLeft;
    }

    public int handBackTotal() {
        return handBackTotal;
    }

    public int latchedKeys() {
        return latchedKeys;
    }

    public int overrideTicks() {
        return overrideTicks;
    }

    public boolean planLost() {
        return planLost;
    }
}
