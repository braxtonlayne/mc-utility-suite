package com.utilitysuite.combat.assistant;

/**
 * Shield handling: when the target blocks and an axe sits in the hotbar (and the held weapon is not already that axe),
 * select the axe, swing once (an axe hit on a raised shield disables it for 100 ticks: vanilla 1.21.1
 * {@code Player.blockUsingShield} -> {@code disableShield}, {@code getCooldowns().addCooldown(item, 100)}), then select
 * the main weapon again. Gives up and restores the weapon if the swing is not possible within {@link #MAX_WAIT} ticks or
 * the target stops blocking.
 * <p>
 * A give-up while the target keeps its shield up (a turtling opponent just outside reach) starts a back-off before the
 * next try: {@link #BACKOFF_BASE} ticks, doubled on every further give-up up to {@link #BACKOFF_MAX}, so the hotbar
 * does not flick between the axe and the weapon every eleven ticks. During the back-off {@link #active()} is false and
 * the rest of the fight loop (the shield duel stage) runs. A landed swing, a lowered shield or {@link #reset()} clears
 * the back-off. Allocation free.
 */
public final class ShieldBreakSequencer {

    public enum Action { NONE, SELECT_AXE, SWING, SELECT_WEAPON }

    private enum Phase { IDLE, AXE_SELECTED, SWUNG }

    static final int MAX_WAIT = 10;
    /** Back-off after the first give-up, in ticks. */
    static final int BACKOFF_BASE = 10;
    /** Longest back-off (4 s). */
    static final int BACKOFF_MAX = 80;

    private Phase phase = Phase.IDLE;
    private int waited;
    /** Ticks left before the next try is allowed. */
    private int backoff;
    /** Give-ups in a row against the same raised shield. */
    private int giveUps;

    /** True while the sequence owns the hotbar and the swing (the normal swing logic must not run). */
    public boolean active() {
        return phase != Phase.IDLE;
    }

    /** Ticks left in the current back-off (0 = a new try is allowed). */
    public int backoff() {
        return backoff;
    }

    /** Pure opportunity check, shared with the coach. */
    public static boolean opportunity(CombatSnapshot s) {
        return s.targetBlocking && s.axeSlot >= 0 && s.axeSlot != s.selectedSlot;
    }

    public Action step(CombatSnapshot s, boolean enabled) {
        switch (phase) {
            case IDLE:
                if (!s.targetBlocking) {
                    // the shield came down: the next raise is a fresh situation
                    backoff = 0;
                    giveUps = 0;
                    return Action.NONE;
                }
                if (backoff > 0) {
                    backoff--;
                    return Action.NONE;
                }
                if (enabled && opportunity(s) && s.inReach()) {
                    phase = Phase.AXE_SELECTED;
                    waited = 0;
                    return Action.SELECT_AXE;
                }
                return Action.NONE;
            case AXE_SELECTED:
                if (!s.targetBlocking) {
                    phase = Phase.IDLE;
                    return Action.SELECT_WEAPON;
                }
                if (++waited > MAX_WAIT) {
                    phase = Phase.IDLE;
                    backoff = Math.min(BACKOFF_MAX, BACKOFF_BASE << Math.min(giveUps, 4));
                    giveUps++;
                    return Action.SELECT_WEAPON;
                }
                if (s.selectedSlot == s.axeSlot && s.inReach() && s.crosshairOnTarget) {
                    phase = Phase.SWUNG;
                    return Action.SWING;
                }
                return Action.NONE;
            case SWUNG:
            default:
                phase = Phase.IDLE;
                giveUps = 0;
                backoff = 0;
                return Action.SELECT_WEAPON;
        }
    }

    public void reset() {
        phase = Phase.IDLE;
        waited = 0;
        backoff = 0;
        giveUps = 0;
    }
}
