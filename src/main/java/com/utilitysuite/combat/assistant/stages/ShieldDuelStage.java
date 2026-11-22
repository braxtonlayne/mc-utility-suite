package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.assistant.SwingGate;
import com.utilitysuite.combat.assistant.TradeDriver;
import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.ShieldDuel;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;

import java.util.List;

/**
 * The shield duel as a {@link FightStage} in {@link Slot#PRE_SWING}: thin glue over {@link ShieldDuel}. It shapes the
 * built-in loop with one-tick requests and returns false, so the aim and the movement stay the built-in ones.
 * <ol>
 *   <li>Their shield: no swing (and no crit hop) while their raised shield faces us and already blocks
 *   ({@link ShieldDuel#frontal}, {@link ShieldDuel#blocksHit}); circle toward the open side the shorter way
 *   ({@link FightContext#forceSide}, hazards respected); a tap on the tick it comes down.</li>
 *   <li>Their shield disabled (an axe hit observed by {@link ShieldFacts#targetShieldDisabledTicks}): recorded in
 *   {@link OpponentModel#noteShieldDisabled}; for the window, crits and no feints every tick.</li>
 *   <li>Our shield (setting {@value #KEY_BLOCK}): block-and-answer against a sword or bare hand, never into an axe,
 *   never while eating, only with the opponent in our front cone; USE goes through {@link FightContext#hold}.</li>
 *   <li>The trade solver hears both shield states through {@link TradeDriver#shieldFeed()}.</li>
 * </ol>
 * {@link #step} is allocation free; the "off" path is two field reads and a release check.
 */
public final class ShieldDuelStage implements FightStage {

    public static final String ID = "shield_duel";
    /** Master setting: never feed a frontal shield, circle it, press the axe-disable window. */
    public static final String KEY_DUEL = "shield_duel";
    /** Our own shield: block-and-answer. */
    public static final String KEY_BLOCK = "shield_duel_block";
    static final String GROUP = "Shield & weapons";

    /** Vanilla entity interaction range of a player (the opponent's reach of us). */
    static final double OPP_REACH = 3.0;

    private final ShieldFacts facts;
    private final ShieldDuel duel = new ShieldDuel();

    private boolean enabled = true;
    private boolean blockEnabled = true;

    /** We hold USE for our shield (through the context). */
    private boolean holdingUse;
    /** Their frontal shield blocked last tick. */
    private boolean theirUpBefore;
    /** Last side forced, kept while circling (also the tie-break when exactly in front). */
    private int lastSide = ShieldDuel.LEFT;
    private int vetoes;
    private int drops;

    public ShieldDuelStage(ShieldFacts facts) {
        this.facts = facts == null ? ShieldFacts.NONE : facts;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Slot slot() {
        return Slot.PRE_SWING;
    }

    @Override
    public boolean step(CombatSnapshot s, FightContext ctx) {
        if (!enabled) {
            lowerOwn(ctx);
            return false;
        }
        int id = ctx.targetId();
        long now = ctx.clock();
        ShieldFacts f = facts;
        f.refresh(id);
        OpponentModel om = ctx.opponents();
        boolean known = f.targetKnown();

        // ---- their shield disabled: record the window from the observation, then press it ----
        int dis = known ? f.targetShieldDisabledTicks() : -1;
        if (dis > 0) {
            om.noteShieldDisabled(id, now + dis);
        }
        boolean disabled = om.shieldDisabled(id, now);

        // ---- their shield: geometry (head yaw, as isDamageSourceBlocked) and the 5-tick raise delay ----
        float hy = known ? f.targetHeadYaw() : Float.NaN;
        float theirYaw = Float.isNaN(hy) ? s.targetYaw : hy;
        int theirUse = known ? f.targetShieldUseTicks() : 0;
        if (s.targetBlocking && theirUse < ShieldDuel.RAISE_DELAY) {
            theirUse = ShieldDuel.RAISE_DELAY; // vanilla isBlocking() already passed the delay
        }
        boolean frontal = ShieldDuel.frontal(theirYaw, s.tx, s.tz, s.px, s.pz);
        boolean theirUp = !disabled && frontal && ShieldDuel.blocksHit(theirUse, ShieldDuel.HIT_LEAD);

        // ---- our shield: block and answer ----
        ShieldDuel d = duel;
        boolean eating = ctx.eating();
        d.blockEnabled = blockEnabled;
        d.hasShield = f.ownShieldReady();
        d.eating = eating;
        d.oppDisables = f.targetHoldsAxe() || s.targetHeavyHitter;
        d.oppInCone = ShieldDuel.inFrontCone(s.yaw, s.px, s.pz, s.tx, s.tz, ShieldDuel.FRONT_CONE_DEG);
        d.oppInReach = Math.sqrt(s.reachDistanceSq()) <= OPP_REACH + ShieldDuel.REACH_MARGIN;
        d.oppShieldUp = theirUse > 0 && !disabled;
        d.oppReadyIn = om.attackReadyIn(id, now);
        boolean up = d.step();
        if (eating) {
            holdingUse = false; // the sustain layer owns USE now and releases it when it is done
        } else if (up) {
            ctx.hold(SimKey.USE, true); // cached in the assistant: repeated holds cost nothing
            holdingUse = true;
        } else {
            lowerOwn(ctx);
        }

        // ---- swing control ----
        if (d.vetoSwing()) {
            ctx.vetoSwing(); // attack clicks are eaten while our shield is up (Minecraft.handleKeybinds)
            ctx.vetoJump();
        }
        if (theirUp) {
            ctx.vetoSwing();
            ctx.vetoJump();
            lastSide = ShieldDuel.openSide(theirYaw, s.tx, s.tz, s.px, s.pz, s.hazardLeft, s.hazardRight, lastSide);
            ctx.forceSide(lastSide);
            vetoes++;
        } else if (theirUpBefore && !d.vetoSwing() && SwingGate.ready(s.attackStrength) && s.inReach()
                && s.crosshairOnTarget) {
            // they lowered it (to swing, or it was disabled): our charged hit goes in on this tick
            ctx.tapAttack();
            ctx.vetoSwing(); // no second tap from the hit planner
            drops++;
        }
        theirUpBefore = theirUp;

        if (disabled) {
            ctx.hitPolicy(HitPlanner.Policy.CRITS);
            ctx.pressAdvantage();
        }
        TradeDriver.shieldFeed().set(now, up && !eating, theirUp);
        return false;
    }

    private void lowerOwn(FightContext ctx) {
        if (holdingUse) {
            holdingUse = false;
            if (!ctx.eating()) {
                ctx.hold(SimKey.USE, false);
            }
        }
    }

    @Override
    public void onEngage(FightContext ctx) {
        lowerOwn(ctx);
        clearState();
    }

    @Override
    public void reset() {
        // the assistant releases every key when the fight ends; forget that we held USE
        holdingUse = false;
        clearState();
    }

    private void clearState() {
        duel.reset();
        theirUpBefore = false;
        lastSide = ShieldDuel.LEFT;
        TradeDriver.shieldFeed().clear();
    }

    @Override
    public void specs(List<SettingSpec> out) {
        out.add(SettingSpec.bool(KEY_DUEL, "Shield duel", true, GROUP,
                "Never swing into a raised shield that faces you: circle to its open side and hit the moment it "
                        + "comes down. After an axe disables it, press with crits for the 5 seconds."));
        out.add(SettingSpec.bool(KEY_BLOCK, "Block with shield", true, GROUP,
                "With a shield in the offhand: raise it just before the opponent's charged hit, take the hit on it, "
                        + "lower and answer inside their cooldown. Never against an axe, never while eating, only "
                        + "with the opponent in front of you."));
    }

    @Override
    public void read(ProfileConfig profile, String moduleId) {
        enabled = profile.getBool(moduleId, KEY_DUEL, true);
        blockEnabled = profile.getBool(moduleId, KEY_BLOCK, true);
    }

    // ---- diagnostics and tests ------------------------------------------------------------------------------------

    public boolean enabled() {
        return enabled;
    }

    public boolean blockEnabled() {
        return blockEnabled;
    }

    /** Settings without a profile (tests). */
    public void configure(boolean duel, boolean block) {
        this.enabled = duel;
        this.blockEnabled = block;
    }

    /** USE is held by this stage. */
    public boolean holdingUse() {
        return holdingUse;
    }

    /** Ticks a swing was held back by a frontal shield. */
    public int vetoes() {
        return vetoes;
    }

    /** Taps sent on the tick their shield came down. */
    public int drops() {
        return drops;
    }
}
