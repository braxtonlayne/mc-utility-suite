package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.assistant.SustainPlanner;
import com.utilitysuite.combat.tactics.PotionPlanner;
import com.utilitysuite.core.api.SimKey;

/**
 * Combat potions in fights ({@link CombatSettings#combatPotions}, off by default; {@link Slot#SUSTAIN}). Drives the
 * engine-free {@link PotionPlanner}:
 * <ul>
 *   <li>DRINK: the potion's hotbar slot is selected through the core and USE is held by the built-in sustain layer
 *       ({@link FightContext#startEating}: it backs off facing the target, finishes and restores the weapon), so a
 *       drink needs "Emergency sustain" on, like the healing windows.</li>
 *   <li>Splash: the potion slot is selected and the camera eased down to the feet through the smoother
 *       ({@link FightContext#aimAt}, speed-capped in core); the throw (one USE tap) goes out only once the smoothed
 *       pitch arrived ({@link PotionPlanner#THROW_MIN_PITCH}) and the potion has been in hand for a tick; the weapon
 *       comes back on the following tick.</li>
 * </ul>
 * The built-in emergency (totem, the low-health golden apple) keeps priority: whenever {@link SustainPlanner#decide}
 * asks for either the stage steps aside (a plain retreat does not stop a potion). Allocation free; facts refreshed every {@value #FACTS_INTERVAL} ticks.
 */
public final class PotionStage implements FightStage {

    public static final String ID = "combat_potions";

    public static final int FACTS_INTERVAL = 10;
    /** "Being chased": the target is this close (blocks) and closing in, or already in reach. */
    public static final double CHASE_DISTANCE = 6.0;
    public static final double CHASE_SPEED = 0.05;

    private final HealFacts facts;
    private final PotionPlanner planner = new PotionPlanner();

    private int factsIn;
    /** A splash is ready to go out but the potion was not in hand yet. */
    private boolean throwPending;
    /** Ticks left until the weapon is selected again after a throw. */
    private int restoreIn;
    private int drinks;
    private int throwsMade;

    public PotionStage(HealFacts facts) {
        this.facts = facts;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Slot slot() {
        return Slot.SUSTAIN;
    }

    @Override
    public boolean step(CombatSnapshot s, FightContext ctx) {
        CombatSettings cs = ctx.settings();
        if (!cs.combatPotions) {
            return false;
        }
        if (restoreIn > 0 && --restoreIn == 0) {
            ctx.selectWeapon(); // the throw tick has passed: back to the weapon
        }
        if (ctx.eating()) {
            return false; // a drink (or an eat) in progress belongs to the sustain layer
        }
        if (--factsIn <= 0) {
            factsIn = FACTS_INTERVAL;
            facts.refresh();
        }
        if (throwPending) {
            return finishThrow(s, ctx);
        }
        SustainPlanner.Action hard = SustainPlanner.decide(s, cs.lowHealth, ctx.lastHitDamage(), cs.totems,
                cs.gapples);
        if (hard == SustainPlanner.Action.EQUIP_TOTEM || hard == SustainPlanner.Action.EAT) {
            return false; // the totem and the emergency golden apple own this tick (a RETREAT is where potions help)
        }
        PotionPlanner p = planner;
        p.enabled = true;
        p.healHealth = cs.potionHealHealth;
        p.regenHealth = cs.potionRegenHealth;
        p.criticalHealth = cs.pressureCritical;
        p.pressureTicks = cs.popPressure ? cs.pressureTicks : 0;
        p.preferSplashWhenChased = cs.splashWhenChased;
        p.health = s.health;
        p.absorption = s.absorption;
        p.regenerating = facts.regenerating();
        p.drinkHealSlot = cs.sustain ? facts.drinkHealingSlot() : -1;
        p.drinkRegenSlot = cs.sustain ? facts.drinkRegenSlot() : -1;
        p.splashHealSlot = facts.splashHealingSlot();
        p.splashRegenSlot = facts.splashRegenSlot();
        p.beingChased = chased(s);
        p.onGround = s.onGround;
        p.inWater = s.inWater;
        p.usingItem = s.usingItem;
        p.screenOpen = s.screenOpen;
        p.manualOverride = false; // the assistant pauses every stage while the user moves (manual input wins)
        p.pitch = s.pitch;
        switch (p.step(ctx.clock())) {
            case DRINK -> {
                ctx.startEating(s, p.slot());
                factsIn = 0;
                drinks++;
                return false; // the sustain layer carries the drink on this same tick
            }
            case AIM_FEET -> {
                return aimFeet(s, ctx);
            }
            case THROW_SPLASH -> {
                throwPending = true;
                return finishThrow(s, ctx);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean aimFeet(CombatSnapshot s, FightContext ctx) {
        ctx.selectHotbar(planner.slot());
        ctx.aimAt(s.yaw, PotionPlanner.FEET_PITCH, ctx.settings().aimSpeed);
        ctx.retreatMove(s);
        return true;
    }

    private boolean finishThrow(CombatSnapshot s, FightContext ctx) {
        int slot = planner.slot();
        if (slot < 0 || s.screenOpen) {
            throwPending = false;
            return false;
        }
        ctx.aimAt(s.yaw, PotionPlanner.FEET_PITCH, ctx.settings().aimSpeed);
        ctx.retreatMove(s);
        if (s.selectedSlot != slot || s.pitch < PotionPlanner.THROW_MIN_PITCH) {
            ctx.selectHotbar(slot); // in hand for a full tick before the throw
            return true;
        }
        ctx.tap(SimKey.USE, 1, 0);
        throwPending = false;
        restoreIn = 2;
        factsIn = 0;
        throwsMade++;
        return true;
    }

    static boolean chased(CombatSnapshot s) {
        if (s.inReach()) {
            return true;
        }
        double dx = s.px - s.tx;
        double dz = s.pz - s.tz;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > CHASE_DISTANCE || d < 1.0e-6) {
            return false;
        }
        return (s.tvx * dx + s.tvz * dz) / d > CHASE_SPEED;
    }

    /** The existing pop detection reports an opponent totem pop (post-pop pressure). */
    public void onOpponentPop(long clock) {
        planner.onOpponentPop(clock);
    }

    @Override
    public void onEngage(FightContext ctx) {
        throwPending = false;
        factsIn = 0;
    }

    @Override
    public void reset() {
        planner.reset();
        throwPending = false;
        restoreIn = 0;
        factsIn = 0;
    }

    // ---- diagnostics and tests ------------------------------------------------------------------------------------

    public int drinks() {
        return drinks;
    }

    public int throwsMade() {
        return throwsMade;
    }

    public PotionPlanner planner() {
        return planner;
    }
}
