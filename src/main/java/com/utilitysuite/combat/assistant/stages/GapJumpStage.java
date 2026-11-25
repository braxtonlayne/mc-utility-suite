package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.tactics.CombatGapJump;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.movement.JumpSearch;
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
    private int runUps;

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
            if (CombatGapJump.REASON_VALIDATOR.equals(gate.reason()) && runUpLands(s, yaw, w)) {
                // not at the edge yet (the fight's own spacing stops short of it): sprint up to the edge; the gate
                // above approves the jump on the last supported tick
                ctx.move(1, 0, true);
                runUps++;
                return true;
            }
            return false;
        }
        ctx.move(1, 0, true);
        ctx.tap(SimKey.JUMP, 1, 0);
        jumps++;
        return true;
    }

    /**
     * Run-up check (playtest 2026-11-25: fight movement held ~0.9 blocks short of a 2-wide gap, so the validator never
     * saw an edge): the edge is at least one more supported tick ahead along {@code yaw} (within
     * {@link JumpSearch#EDGE_WALK_MAX}), and running to it and jumping there lands unhurt, no lower than now and closer
     * to the target. On the edge tick itself the gate decides as before, so a refused jump never walks off.
     */
    private boolean runUpLands(CombatSnapshot s, float yaw, MovementPhysics.Voxels w) {
        JumpSearch js = gate.search();
        if (js.ticksToEdge(body, 1, 0, yaw, true, JumpSearch.EDGE_WALK_MAX, w) < 1) {
            return false; // no edge ahead, or this is already the edge tick (the gate's call)
        }
        if (js.edgeRun(body, 1, 0, yaw, true, s.effectiveHealth(), w) <= JumpSearch.NO_LANDING / 2
                || js.landDamage() > 0f || js.landY() < s.py - 0.5) {
            return false;
        }
        double bx = s.px - s.tx, bz = s.pz - s.tz, lx = js.landX() - s.tx, lz = js.landZ() - s.tz;
        return lx * lx + lz * lz < bx * bx + bz * bz;
    }

    // ---- diagnostics and tests ------------------------------------------------------------------------------------

    public int runUps() {
        return runUps;
    }

    public int jumps() {
        return jumps;
    }

    public String lastReason() {
        return gate.reason();
    }
}
