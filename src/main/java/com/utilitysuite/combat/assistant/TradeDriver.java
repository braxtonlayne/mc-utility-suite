package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.HitTradeSolver;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.TradeInput;

/**
 * Feeds the hit-trade solver and smooths its answer into keys a person could press. Each tick it fills one reused
 * {@link TradeInput} from the combat snapshot and the opponent model, runs the solve step and applies a dwell: a key
 * combination is held for at least {@value #MIN_DWELL} ticks unless a new one scores clearly better
 * ({@value #SWITCH_MARGIN} hits) or the held one became unusable, so the keys never flicker tick by tick.
 * <p>
 * Engine free and allocation free after construction. The solve step is a small seam: production wraps a
 * {@link HitTradeSolver}; tests script the answers.
 */
public final class TradeDriver {

    /** One solve of the nine key combinations (the shape of {@link HitTradeSolver}). */
    public interface Step {
        void solve(TradeInput in);

        boolean valid();

        int forward();

        int strafe();

        boolean jump();

        boolean sprint();

        double score();

        /** Score of one combination this tick ({@link HitTradeSolver#combo}); very negative when rejected. */
        double scoreOf(int combo);
    }

    /** Minimum ticks an answer is held before another one may replace it on a small score difference. */
    public static final int MIN_DWELL = 3;
    /** Score lead (in hits) a new answer needs to replace one held shorter than {@link #MIN_DWELL}. */
    public static final double SWITCH_MARGIN = 0.25;
    /** Below this score a combination counts as rejected by the solver. */
    static final double REJECTED = -1.0e6;
    /** Own cooldown period assumed before a rise of the attack strength was seen (a sword: 20 / 1.6). */
    public static final double DEFAULT_COOLDOWN = 12.5;
    /** Opponent speed (blocks per tick) below which its closing or lateral motion counts as none. */
    public static final double DEAD_ZONE = 0.03;
    /** Opponent horizontal speed (blocks per tick) above which it is assumed to sprint. */
    static final double SPRINT_SPEED = 0.22;

    private final Step step;
    private final TradeInput in = new TradeInput();

    // own cooldown estimate
    private float lastStrength = -1f;
    private double ownCooldown = DEFAULT_COOLDOWN;

    // dwell
    private boolean holding;
    private int heldTicks;
    private int forward;
    private int strafe;
    private boolean sprint;
    private boolean jump;

    public TradeDriver(Step step) {
        this.step = step;
    }

    /** Production path: wraps a solver (one adapter object, made once). */
    public static TradeDriver of(HitTradeSolver solver) {
        return new TradeDriver(new Step() {
            @Override
            public void solve(TradeInput in) {
                solver.solve(in);
            }

            @Override
            public boolean valid() {
                return solver.valid();
            }

            @Override
            public int forward() {
                return solver.forward();
            }

            @Override
            public int strafe() {
                return solver.strafe();
            }

            @Override
            public boolean jump() {
                return solver.jump();
            }

            @Override
            public boolean sprint() {
                return solver.sprint();
            }

            @Override
            public double score() {
                return solver.score();
            }

            @Override
            public double scoreOf(int combo) {
                return solver.scoreOf(combo);
            }
        });
    }

    /** The reused input (filled by {@link #fill}); exposed for tests and diagnostics. */
    public TradeInput input() {
        return in;
    }

    /**
     * Fills the solver input for this tick. Allocation free.
     *
     * @param opponentPlayer the target swings on a cooldown (a player); mobs hit whenever in reach
     * @param readCooldown   false = the opponent is treated as always ready
     * @param takenWeight    value of a hit taken relative to one landed (larger while kiting)
     * @param allowJump      false at the lip of a gap or while crit sync is waiting on its own hop
     */
    public void fill(CombatSnapshot s, OpponentModel opponents, int targetId, long now, boolean opponentPlayer,
                     boolean readCooldown, int horizon, double takenWeight, boolean allowJump) {
        TradeInput t = in;
        t.sx = s.px;
        t.sy = s.py;
        t.sz = s.pz;
        t.svx = s.pvx;
        t.svy = s.pvy;
        t.svz = s.pvz;
        t.sOnGround = s.onGround;
        t.sYaw = s.yaw;
        t.sSprinting = s.sprinting;
        t.sReach = s.reach;
        noteOwnStrength(s.attackStrength);
        t.sCooldownTicks = ownCooldown;
        t.sReadyIn = readyIn(s.attackStrength, ownCooldown);

        t.ox = s.tx;
        t.oy = s.ty;
        t.oz = s.tz;
        t.ovx = s.tvx;
        t.ovy = s.tvy;
        t.ovz = s.tvz;
        t.oOnGround = s.targetOnGround;
        t.oReach = 3.0;
        t.oUsesCooldown = opponentPlayer;
        t.oCooldownTicks = opponents.cooldownTicks(targetId);
        t.oReadyIn = readCooldown ? Math.max(0, opponents.attackReadyIn(targetId, now)) : 0;

        // opponent keys relative to facing us: closing speed along the bearing toward us, lateral sign to its left
        double bx = s.px - s.tx;
        double bz = s.pz - s.tz;
        double len = Math.sqrt(bx * bx + bz * bz);
        if (len > 1.0e-6) {
            bx /= len;
            bz /= len;
            double closing = s.tvx * bx + s.tvz * bz;
            // facing f = (bx, bz); Minecraft's left of a body facing f is (fz, -fx)
            double lateral = s.tvx * bz - s.tvz * bx;
            t.oForward = closing > DEAD_ZONE ? 1 : closing < -DEAD_ZONE ? -1 : 0;
            t.oStrafe = lateral > DEAD_ZONE ? 1 : lateral < -DEAD_ZONE ? -1 : 0;
        } else {
            t.oForward = 0;
            t.oStrafe = 0;
        }
        t.oSprinting = s.tvx * s.tvx + s.tvz * s.tvz > SPRINT_SPEED * SPRINT_SPEED;

        t.hazardFront = s.hazardFront;
        t.hazardBack = s.hazardBack;
        t.hazardLeft = s.hazardLeft;
        t.hazardRight = s.hazardRight;
        t.horizon = Math.max(6, Math.min(8, horizon));
        t.takenWeight = takenWeight;
        t.allowJump = allowJump;
    }

    /**
     * Fills the input, solves and applies the dwell. Afterwards {@link #forward()}, {@link #strafe()},
     * {@link #sprint()} and {@link #jump()} hold this tick's keys.
     *
     * @return false when the solver had no valid answer (the caller falls back to its spacing band)
     */
    public boolean decide(CombatSnapshot s, OpponentModel opponents, int targetId, long now, boolean opponentPlayer,
                          boolean readCooldown, int horizon, double takenWeight, boolean allowJump) {
        fill(s, opponents, targetId, now, opponentPlayer, readCooldown, horizon, takenWeight, allowJump);
        step.solve(in);
        return applyAnswer();
    }

    /** Dwell over the step's current answer (split out so tests can drive it without a snapshot). */
    boolean applyAnswer() {
        Step st = step;
        if (!st.valid()) {
            reset();
            return false;
        }
        int f = st.forward();
        int sd = st.strafe();
        boolean same = holding && f == forward && sd == strafe;
        if (!holding || same) {
            take(f, sd, st, same);
            return true;
        }
        double heldNow = st.scoreOf(HitTradeSolver.combo(forward, strafe));
        boolean heldRejected = heldNow <= REJECTED;
        if (heldRejected || heldTicks >= MIN_DWELL || st.score() > heldNow + SWITCH_MARGIN) {
            take(f, sd, st, false);
        } else {
            heldTicks++;
            jump = false; // keep the held keys; a jump belongs to the answer that was not taken
        }
        return true;
    }

    private void take(int f, int sd, Step st, boolean same) {
        heldTicks = same ? heldTicks + 1 : 1;
        holding = true;
        forward = f;
        strafe = sd;
        sprint = st.sprint() && f > 0;
        jump = st.jump();
    }

    /** Forgets the held answer (the solver was not used this tick, or a new engagement). */
    public void reset() {
        holding = false;
        heldTicks = 0;
        forward = 0;
        strafe = 0;
        sprint = false;
        jump = false;
    }

    /** Also forgets the own-cooldown estimate (new engagement). */
    public void resetAll() {
        reset();
        lastStrength = -1f;
        ownCooldown = DEFAULT_COOLDOWN;
    }

    private void noteOwnStrength(float strength) {
        float last = lastStrength;
        lastStrength = strength;
        if (last < 0f) {
            return;
        }
        double rise = strength - last;
        // a clean rise while charging (not the reset after a swing, not the clamp at 1.0)
        if (rise > 0.02 && rise < 0.6 && strength < 0.999f) {
            double period = 1.0 / rise;
            ownCooldown += (period - ownCooldown) * 0.3;
        }
    }

    static int readyIn(float strength, double period) {
        if (strength >= 0.999f) {
            return 0;
        }
        return (int) Math.ceil((1.0 - strength) * period);
    }

    /** Estimated own cooldown period in ticks. */
    public double ownCooldown() {
        return ownCooldown;
    }

    /** +1 forward key, -1 back, 0 none. */
    public int forward() {
        return forward;
    }

    /** Solver convention: +1 left key, -1 right, 0 none. */
    public int strafe() {
        return strafe;
    }

    public boolean sprint() {
        return sprint;
    }

    public boolean jump() {
        return jump;
    }

    /** Ticks the current answer has been held. */
    public int heldTicks() {
        return heldTicks;
    }
}
