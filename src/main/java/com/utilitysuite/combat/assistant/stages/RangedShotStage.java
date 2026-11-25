package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.AimSolver;
import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.tactics.RangedBallistics;
import com.utilitysuite.core.api.SimKey;

/**
 * Optional ranged mode of the Combat Assistant ({@link CombatSettings#rangedAim}, off by default). With a bow,
 * crossbow or trident in the main hand and the target beyond melee reach and in sight, it solves the launch pitch for
 * the projectile drop with lead on the target's velocity ({@link RangedBallistics}), feeds that look to the smoother
 * through {@link FightContext#aimAt} (smoothed and speed-capped in core like every look), holds still, draws, and
 * releases only at full charge while the camera has reached the solution. No solution (out of range): it keeps
 * facing the target and holds the draw.
 * <p>
 * It never switches items: holding the ranged weapon is the player's choice. Line of sight is required (the built-in
 * loop's convention). Allocation free.
 */
public final class RangedShotStage implements FightStage {

    public static final String ID = "ranged_shot";

    /** Melee takes over within reach plus this margin. */
    public static final double MELEE_MARGIN = 1.0;
    /** Camera within this many degrees of the solution counts as aimed. */
    public static final float AIM_TOLERANCE_DEG = 1.0f;
    public static final int TRIDENT_CHARGE_TICKS = 10;
    /** USE held this long without the item starting to be used: no ammo (or blocked); back off. */
    public static final int NO_USE_TICKS = 6;
    public static final int NO_AMMO_BACKOFF = 100;
    /** Arrows leave 0.1 below the eye. */
    public static final double LAUNCH_DROP = 0.1;

    private final RangedFacts facts;
    private final RangedBallistics ballistics = new RangedBallistics();

    private boolean holding;
    private int heldTicks;
    private int backoff;
    private int tapCooldown;
    private int shots;
    private boolean lastSolved;
    private float lastYaw, lastPitch;

    public RangedShotStage(RangedFacts facts) {
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
        if (!cs.rangedAim) {
            release(ctx);
            return false;
        }
        if (backoff > 0) {
            backoff--;
        }
        if (tapCooldown > 0) {
            tapCooldown--;
        }
        facts.refresh();
        int weapon = facts.weapon();
        double cx = (s.minX + s.maxX) * 0.5, cy = (s.minY + s.maxY) * 0.5, cz = (s.minZ + s.maxZ) * 0.5;
        double dx = cx - s.px, dz = cz - s.pz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (weapon == RangedFacts.NONE || backoff > 0 || ctx.eating() || s.screenOpen || !s.lineOfSight
                || dist <= s.reach + MELEE_MARGIN) {
            release(ctx);
            return false;
        }
        double speed = switch (weapon) {
            case RangedFacts.CROSSBOW -> RangedBallistics.CROSSBOW_SPEED;
            case RangedFacts.TRIDENT -> RangedBallistics.TRIDENT_SPEED;
            default -> RangedBallistics.BOW_FULL_SPEED; // solved for the full draw it releases at
        };
        double waterDrag = weapon == RangedFacts.TRIDENT ? RangedBallistics.TRIDENT_WATER_DRAG
                : RangedBallistics.ARROW_WATER_DRAG;
        double oy = s.eyeY - LAUNCH_DROP;
        lastSolved = ballistics.solveLead(s.px, oy, s.pz, cx, cy, cz, s.tvx, s.tvy, s.tvz, speed, waterDrag,
                s.inWater);
        if (lastSolved) {
            double ax = ballistics.aimX() - s.px, az = ballistics.aimZ() - s.pz;
            lastYaw = (float) Math.toDegrees(Math.atan2(-ax, az));
            lastPitch = ballistics.mcPitch();
            ctx.aimAt(lastYaw, lastPitch, cs.aimSpeed);
        } else {
            ctx.aimAtTarget(s);
        }
        ctx.move(0, 0, false);
        boolean aimed = lastSolved && Math.abs(AimSolver.yawDelta(s.yaw, lastYaw)) <= AIM_TOLERANCE_DEG
                && Math.abs(s.pitch - lastPitch) <= AIM_TOLERANCE_DEG;
        int use = facts.useTicks();
        if (weapon == RangedFacts.CROSSBOW && facts.crossbowCharged()) {
            if (holding) {
                release(ctx);
            }
            if (aimed && tapCooldown == 0) {
                ctx.tap(SimKey.USE, 1, 0);
                tapCooldown = 4;
                shots++;
            }
            return true;
        }
        if (!holding) {
            ctx.hold(SimKey.USE, true);
            holding = true;
            heldTicks = 0;
            return true;
        }
        heldTicks++;
        if (use == 0 && heldTicks >= NO_USE_TICKS) {
            release(ctx);
            backoff = NO_AMMO_BACKOFF;
            return false;
        }
        boolean full = switch (weapon) {
            case RangedFacts.CROSSBOW -> use >= facts.crossbowChargeTicks();
            case RangedFacts.TRIDENT -> use >= TRIDENT_CHARGE_TICKS;
            default -> use >= RangedBallistics.BOW_FULL_CHARGE_TICKS;
        };
        if (weapon == RangedFacts.CROSSBOW) {
            if (full) {
                release(ctx); // loads the crossbow; the shot comes on a later tick once aimed
            }
        } else if (full && aimed) {
            release(ctx);
            shots++;
        }
        return true;
    }

    private void release(FightContext ctx) {
        if (holding) {
            ctx.hold(SimKey.USE, false);
            holding = false;
        }
        heldTicks = 0;
    }

    @Override
    public void reset() {
        holding = false;
        heldTicks = 0;
        backoff = 0;
        tapCooldown = 0;
        lastSolved = false;
    }

    // ---- diagnostics and tests ------------------------------------------------------------------------------------

    public boolean holding() {
        return holding;
    }

    public int shots() {
        return shots;
    }

    public boolean lastSolved() {
        return lastSolved;
    }

    public float lastYaw() {
        return lastYaw;
    }

    public float lastPitch() {
        return lastPitch;
    }
}
