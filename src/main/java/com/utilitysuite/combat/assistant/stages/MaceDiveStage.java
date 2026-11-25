package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.tactics.MaceDivePlanner;
import com.utilitysuite.core.api.SimKey;

/**
 * Wind-charge mace launch in fights ({@link CombatSettings#maceDive}, off by default; {@link Slot#PRE_AIM}). Executes
 * a {@link MaceDivePlanner#planWindLaunch} plan:
 * <ol>
 *   <li>AIM: with the target close, a wind charge and a mace in the hotbar, and a feasible plan for the configured
 *       tier, select the wind charge through the core and ease the camera down ({@link MaceDivePlanner#LAUNCH_PITCH},
 *       smoothed and speed-capped in core).</li>
 *   <li>LAUNCH: once the smoothed pitch arrived ({@link MaceDivePlanner#launchStep}) and the charge has been in hand,
 *       jump and throw on the same tick.</li>
 *   <li>AIR: select the mace on the next tick and track the target with the normal smoothed aim; swing at the planned
 *       tick only when {@link MaceDivePlanner#swingAllowed} (mace held at least one full tick, the fall reached the
 *       tier) and the target is in reach.</li>
 *   <li>AFTER: keep the mace for the hit tick ({@link MaceDivePlanner#swapAwayAllowed}), then back to the weapon. No
 *       swap on or around the swing tick.</li>
 * </ol>
 * Steps aside while the built-in wind-charge launch ({@code wind_charge}) is on. Allocation free (one reused plan).
 */
public final class MaceDiveStage implements FightStage {

    public static final String ID = "mace_dive";

    public enum Phase { IDLE, AIM, AIR, AFTER }

    /** Start a launch only with the target box this close horizontally (blocks): the launch is vertical. */
    public static final double MAX_GAP = 1.5;
    public static final int AIM_TIMEOUT = 15;
    /** Ticks between launches. */
    public static final int COOLDOWN = 40;

    private final MaceDivePlanner.Plan plan = new MaceDivePlanner.Plan();
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int maceHeld;
    private int sinceSwing;
    private int cooldown;
    private int swingTick = -1;
    private float required;
    private int launches;
    private int swings;

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
        if (!cs.maceDive || cs.windCharge) {
            if (phase != Phase.IDLE) {
                abort(ctx);
            }
            return false;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        maceHeld = s.maceSlot >= 0 && s.selectedSlot == s.maceSlot ? maceHeld + 1 : 0;
        if (s.screenOpen || ctx.eating()) {
            if (phase != Phase.IDLE) {
                abort(ctx);
            }
            return false;
        }
        phaseTicks++;
        switch (phase) {
            case IDLE -> {
                return tryStart(s, ctx, cs);
            }
            case AIM -> {
                return aim(s, ctx);
            }
            case AIR -> {
                return air(s, ctx);
            }
            default -> {
                // AFTER: the hit tick has passed once a tick went by; then the weapon comes back
                sinceSwing++;
                if (MaceDivePlanner.swapAwayAllowed(sinceSwing)) {
                    ctx.selectWeapon();
                    enter(Phase.IDLE);
                    cooldown = COOLDOWN;
                }
                return false;
            }
        }
    }

    private boolean tryStart(CombatSnapshot s, FightContext ctx, CombatSettings cs) {
        if (cooldown > 0 || !s.onGround || s.inWater || s.usingItem || s.maceSlot < 0 || s.windChargeSlot < 0
                || s.windChargeOnCooldown) {
            return false;
        }
        double gap = horizGap(s);
        if (gap > MAX_GAP) {
            return false;
        }
        required = MaceDivePlanner.fallFor(cs.maceTier);
        MaceDivePlanner.planWindLaunch(plan, s.minY - s.py, s.maxY - s.minY, gap, s.reach, required,
                MaceDivePlanner.WIND_LAUNCH_VY, 0);
        if (!plan.feasible) {
            return false;
        }
        swingTick = plan.swingTick;
        enter(Phase.AIM);
        return aim(s, ctx);
    }

    private boolean aim(CombatSnapshot s, FightContext ctx) {
        if (phaseTicks > AIM_TIMEOUT) {
            abort(ctx);
            return false;
        }
        int step = MaceDivePlanner.launchStep(s.onGround, s.windChargeSlot >= 0 && !s.windChargeOnCooldown,
                s.maceSlot >= 0, plan.feasible, s.pitch);
        if (step < 0) {
            abort(ctx);
            return false;
        }
        ctx.selectHotbar(s.windChargeSlot);
        ctx.aimAt(s.yaw, MaceDivePlanner.LAUNCH_PITCH, ctx.settings().aimSpeed);
        ctx.move(0, 0, false);
        if (step == 1 && s.selectedSlot == s.windChargeSlot) {
            ctx.tap(SimKey.USE, 1, 0);
            ctx.tap(SimKey.JUMP, 1, 0);
            launches++;
            enter(Phase.AIR);
        }
        return true;
    }

    private boolean air(CombatSnapshot s, FightContext ctx) {
        if ((s.onGround && phaseTicks > 2) || phaseTicks > MaceDivePlanner.HORIZON) {
            abort(ctx); // landed without a swing: no hit is better than a hit without the smash
            return false;
        }
        ctx.selectHotbar(s.maceSlot);
        ctx.aimAtTarget(s);
        ctx.move(0, 0, false);
        if (phaseTicks >= swingTick - 1 && s.inReach()
                && MaceDivePlanner.swingAllowed(maceHeld > 0, maceHeld - 1, false, s.fallDistance, required)) {
            ctx.tapAttack();
            swings++;
            sinceSwing = 0;
            enter(Phase.AFTER);
        }
        return true;
    }

    private void abort(FightContext ctx) {
        if (phase != Phase.IDLE) {
            ctx.selectWeapon();
            cooldown = COOLDOWN;
        }
        enter(Phase.IDLE);
    }

    private void enter(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    /** Horizontal distance from the own feet to the nearest point of the target box. */
    static double horizGap(CombatSnapshot s) {
        double dx = Math.max(0.0, Math.max(s.minX - s.px, s.px - s.maxX));
        double dz = Math.max(0.0, Math.max(s.minZ - s.pz, s.pz - s.maxZ));
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public void reset() {
        enter(Phase.IDLE);
        cooldown = 0;
        maceHeld = 0;
    }

    // ---- diagnostics and tests ------------------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    public int launches() {
        return launches;
    }

    public int swings() {
        return swings;
    }

    public int plannedSwingTick() {
        return swingTick;
    }
}
