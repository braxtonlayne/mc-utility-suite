package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.tactics.CombatGapJump;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.movement.MovementPhysics;

/**
 * Gap jumps in fights ({@link CombatSettings#fightGapJumps}, off by default; {@link Slot#MOVEMENT}). While closing in
 * on the target (out of reach), the movement toward it is offered to {@link CombatGapJump}: only a jump the parkour
 * validator approves, that keeps the fight and lands safely (no damage at all with SafeWalk on) is taken. The stage
 * then drives that one tick: forward + sprint toward the target (the aim already looks along the movement) and a
 * JUMP tap through the fight's lease. Otherwise the built-in spacing and strafe run as before.
 * Allocation free (one reused body and gate).
 */
public final class GapJumpStage implements FightStage {

    public static final String ID = "fight_gap_jumps";

    private final GapJumpFacts facts;
    private final CombatGapJump gate;
    private final MovementPhysics body = new MovementPhysics();
    private int jumps;

    public GapJumpStage(GapJumpFacts facts) {
        this(facts, new CombatGapJump());
    }

    public GapJumpStage(GapJumpFacts facts, CombatGapJump gate) {
        this.facts = facts;
        this.gate = gate;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Slot slot() {
        return Slot.MOVEMENT;
    }

    @Override
    public boolean step(CombatSnapshot s, FightContext ctx) {
        CombatSettings cs = ctx.settings();
        if (!cs.fightGapJumps || !s.onGround || s.screenOpen || s.inWater || s.usingItem || ctx.eating()
                || s.inReach()) {
            return false;
        }
        MovementPhysics.Voxels w = facts.voxels();
        if (w == null) {
            return false;
        }
        double dx = s.tx - s.px;
        double dz = s.tz - s.pz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        body.set(s.px, s.py, s.pz, s.pvx, s.pvy, s.pvz, true);
        body.speed = s.selfSpeed;
        body.jumpBoost = s.selfJumpBoost;
        gate.enabled = true;
        boolean hitReady = s.attackStrength >= 0.9f && s.inReach();
        if (gate.decide(body, yaw, true, s.effectiveHealth(), facts.safeWalk(), false, s.tx, s.tz, false, hitReady, w)
                != CombatGapJump.Verdict.JUMP) {
            return false;
        }
        ctx.move(1, 0, true);
        ctx.tap(SimKey.JUMP, 1, 0);
        jumps++;
        return true;
    }

    // ---- diagnostics and tests ------------------------------------------------------------------------------------

    public int jumps() {
        return jumps;
    }

    public String lastReason() {
        return gate.reason();
    }
}
