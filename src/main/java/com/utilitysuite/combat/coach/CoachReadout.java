package com.utilitysuite.combat.coach;

/**
 * What the coach HUD draws this frame. Written by {@link FightCoach#onTick()}, read by the HUD layer (both on the
 * client thread). Reused; never reallocated.
 */
public final class CoachReadout {

    /** False: draw nothing. */
    public boolean visible;
    /** -1 too close, 0 optimal, 1 too far. */
    public int range;
    public boolean swingReady;
    /** Swing now would be a critical hit (ready and falling). */
    public boolean critReady;
    /** Attack recharge 0..1, written by the HUD layer from the player each frame (the cooldown arc). */
    public float charge01;
    public boolean shieldBreak;
    public boolean retreat;
    /** {@link ThreatSource#NONE}, PROJECTILE or BLAST. */
    public int danger;
    public float dangerYawDeg;
    /**
     * The opponent's estimated attack recharge 0..1 (1 = ready, also when never seen swinging) and the ticks until it
     * is full again. Written by {@link FightCoach#onTick()}.
     */
    public float oppCharge01 = 1f;
    public int oppReadyIn;
    /** Ticks our shield stays disabled (an axe hit); 0 = usable. Written by the HUD layer from the player each frame. */
    public int shieldDisabledTicks;

    public void clear() {
        visible = false;
        range = 0;
        swingReady = false;
        critReady = false;
        shieldBreak = false;
        retreat = false;
        danger = ThreatSource.NONE;
        dangerYawDeg = 0f;
        oppCharge01 = 1f;
        oppReadyIn = 0;
    }
}
