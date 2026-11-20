package com.utilitysuite.combat.assistant;

/**
 * Shield handling: when the target blocks and an axe sits in the hotbar (and the held weapon is not already that axe),
 * select the axe, swing once (an axe hit disables the shield regardless of charge), then select the main weapon again.
 * Gives up and restores the weapon if the swing is not possible within {@link #MAX_WAIT} ticks or the target stops
 * blocking. Allocation free.
 */
public final class ShieldBreakSequencer {

    public enum Action { NONE, SELECT_AXE, SWING, SELECT_WEAPON }

    private enum Phase { IDLE, AXE_SELECTED, SWUNG }

    static final int MAX_WAIT = 10;

    private Phase phase = Phase.IDLE;
    private int waited;

    /** True while the sequence owns the hotbar and the swing (the normal swing logic must not run). */
    public boolean active() {
        return phase != Phase.IDLE;
    }

    /** Pure opportunity check, shared with the coach. */
    public static boolean opportunity(CombatSnapshot s) {
        return s.targetBlocking && s.axeSlot >= 0 && s.axeSlot != s.selectedSlot;
    }

    public Action step(CombatSnapshot s, boolean enabled) {
        switch (phase) {
            case IDLE:
                if (enabled && opportunity(s) && s.inReach()) {
                    phase = Phase.AXE_SELECTED;
                    waited = 0;
                    return Action.SELECT_AXE;
                }
                return Action.NONE;
            case AXE_SELECTED:
                if (!s.targetBlocking || ++waited > MAX_WAIT) {
                    phase = Phase.IDLE;
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
                return Action.SELECT_WEAPON;
        }
    }

    public void reset() {
        phase = Phase.IDLE;
        waited = 0;
    }
}
