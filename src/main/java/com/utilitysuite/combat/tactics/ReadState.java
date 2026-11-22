package com.utilitysuite.combat.tactics;

/**
 * What the Combat Assistant currently reads off its target, published once per engaged tick for "The Read" overlay
 * (a ring over the target that fills with its attack cooldown and tilts toward its next strafe). Plain mutable fields:
 * the assistant writes, the overlay reads, both on the client thread; nothing is allocated.
 */
public final class ReadState {

    /** False while not engaged (the overlay draws nothing). */
    public boolean active;
    /** Network id of the target. */
    public int targetId = -1;
    /** The target's attack charge 0..1 (1 = its next hit is a full hit). */
    public float charge = 1f;
    /** Ticks until that charge is full. */
    public int readyIn;
    /**
     * Predicted strafe of the target as a horizontal world direction (unit length, or 0,0 when unknown) scaled by the
     * confidence 0..1.
     */
    public float tiltX, tiltZ;
    /** True on the ticks the assistant steps in on the read (the ring may flash). */
    public boolean steppingIn;

    public void clear() {
        active = false;
        targetId = -1;
        charge = 1f;
        readyIn = 0;
        tiltX = 0f;
        tiltZ = 0f;
        steppingIn = false;
    }
}
