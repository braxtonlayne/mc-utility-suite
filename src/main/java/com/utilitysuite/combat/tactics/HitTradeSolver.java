package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.api.BodySim;
import com.utilitysuite.core.api.IBodySimProvider;

/**
 * Hit-trade solver: for each of the nine movement key combinations (forward/none/back x left/none/right), simulates
 * both fighters a few ticks ahead, knockback included, and scores hits landed minus hits taken. The best combination
 * replaces the fixed spacing band: step in right after the opponent swung, stay just out of reach while its attack is
 * charged. Keys only; it never asks for a rotation.
 * <p>
 * CONTRACT STUB (volley 2026-11-22): the public surface below is fixed so the Combat Assistant can be wired in
 * parallel; the solver task fills in the bodies and may add members, but must not change these signatures.
 * Allocation free after construction.
 */
public final class HitTradeSolver {

    /** Number of key combinations: index = (forward + 1) * 3 + (strafe + 1). */
    public static final int COMBOS = 9;

    private final BodySim self;
    private final BodySim opp;

    private boolean valid;
    private int forward;
    private int strafe;
    private boolean jump;
    private boolean sprint;
    private double score;
    private final double[] scores = new double[COMBOS];

    public HitTradeSolver(IBodySimProvider sims) {
        this.self = sims.newBody();
        this.opp = sims.newBody();
    }

    /**
     * Solves for the current tick. Afterwards {@link #valid()} tells whether any combination was acceptable; when it
     * is false the caller falls back to its spacing band.
     */
    public void solve(TradeInput in) {
        valid = false;
        forward = 0;
        strafe = 0;
        jump = false;
        sprint = false;
        score = 0.0;
        java.util.Arrays.fill(scores, 0.0);
    }

    public boolean valid() {
        return valid;
    }

    /** Best forward axis: +1 forward key, -1 back, 0 none. */
    public int forward() {
        return forward;
    }

    /** Best strafe axis: +1 left key, -1 right, 0 none. */
    public int strafe() {
        return strafe;
    }

    public boolean jump() {
        return jump;
    }

    public boolean sprint() {
        return sprint;
    }

    /** Score of the best combination: hits landed minus weighted hits taken over the horizon, plus position terms. */
    public double score() {
        return score;
    }

    /** Score of one combination (see {@link #COMBOS} for the index); very negative when rejected. */
    public double scoreOf(int combo) {
        return scores[combo];
    }

    public static int combo(int forward, int strafe) {
        return (forward + 1) * 3 + (strafe + 1);
    }
}
