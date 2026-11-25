package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.AimSolver;
import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.tactics.DeflectPlanner;
import com.utilitysuite.core.api.IInputSmoother;

/**
 * Optional fireball / wind charge deflection while engaged ({@link CombatSettings#deflectProjectiles}, off by
 * default). When a ghast fireball or wind charge is heading at us ({@link DeflectPlanner#incoming}) and a strike is
 * reachable under the core's turn-speed cap with time to spare ({@link DeflectPlanner#planStrike}), it looks where the
 * projectile will be (through the smoother, like every look) and swings once it is in reach and under the crosshair
 * cone. When the turn cannot be made legitimately in time it does nothing. Against an opponent remembered to throw
 * wind charges at us ("Opponent memory" on) the incoming check looks further ahead
 * ({@link com.utilitysuite.combat.tactics.memory.RecalledHabits#deflectHorizon}). Allocation free.
 */
public final class DeflectStage implements FightStage {

    public static final String ID = "deflect";

    /** Ray-vs-body radius around our centre that counts as "heading at us". */
    public static final double HIT_RADIUS = 1.5;
    public static final double HORIZON_TICKS = 40.0;
    /** Ticks kept spare on top of the turn. */
    public static final int REACTION_TICKS = 2;
    /** Fraction of the core turn cap the plan assumes (the smoother eases in and out). */
    public static final double CAP_SHARE = 0.6;
    /** Swing when the camera is within this many degrees of the projectile. */
    public static final float SWING_CONE_DEG = 8f;

    private final DeflectFacts facts;
    private final DeflectPlanner planner = new DeflectPlanner();
    private int swings;
    private int skipped;

    public DeflectStage(DeflectFacts facts) {
        this.facts = facts;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Slot slot() {
        return Slot.PRE_AIM;
    }

    @Override
    public boolean step(CombatSnapshot s, FightContext ctx) {
        CombatSettings cs = ctx.settings();
        if (!cs.deflectProjectiles || ctx.eating() || s.screenOpen) {
            return false;
        }
        facts.refresh();
        if (!facts.found()) {
            return false;
        }
        double cy = (s.py + s.eyeY) * 0.5;
        // readiness: against a remembered wind-charge thrower (C6 opponent memory) the plan starts earlier
        double horizon = ctx.recalledHabits().deflectHorizon(HORIZON_TICKS);
        if (!planner.incoming(facts.x(), facts.y(), facts.z(), facts.vx(), facts.vy(), facts.vz(), s.px, cy, s.pz,
                HIT_RADIUS, horizon)) {
            return false;
        }
        // in reach now and inside the crosshair cone: swing (the camera got there on an earlier tick's plan)
        double qx = facts.x() - s.px, qy = facts.y() - s.eyeY, qz = facts.z() - s.pz;
        double qh = Math.sqrt(qx * qx + qz * qz);
        float nowYaw = (float) Math.toDegrees(Math.atan2(-qx, qz));
        float nowPitch = (float) -Math.toDegrees(Math.atan2(qy, Math.max(qh, 1.0e-4)));
        if (qh * qh + qy * qy <= s.reach * s.reach
                && Math.abs(AimSolver.yawDelta(s.yaw, nowYaw)) <= SWING_CONE_DEG
                && Math.abs(s.pitch - nowPitch) <= SWING_CONE_DEG) {
            ctx.aimAt(nowYaw, nowPitch, cs.aimSpeed);
            ctx.move(0, 0, false);
            ctx.tapAttack();
            swings++;
            return true;
        }
        double cap = IInputSmoother.MAX_TURN_DEG_PER_SEC * 0.05 * CAP_SHARE * Math.min(1.0, cs.aimSpeed);
        if (!planner.planStrike(facts.x(), facts.y(), facts.z(), facts.vx(), facts.vy(), facts.vz(), s.px, s.eyeY,
                s.pz, s.yaw, s.pitch, s.reach, cap, REACTION_TICKS)) {
            skipped++;
            return false; // not reachable legitimately in time: leave the fight loop alone
        }
        ctx.aimAt(planner.strikeYaw(), planner.strikePitch(), cs.aimSpeed);
        ctx.move(0, 0, false);
        return true;
    }

    // ---- diagnostics and tests ------------------------------------------------------------------------------------

    public int swings() {
        return swings;
    }

    public int skipped() {
        return skipped;
    }
}
