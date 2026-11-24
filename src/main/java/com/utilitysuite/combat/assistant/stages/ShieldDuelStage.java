package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.assistant.SwingGate;
import com.utilitysuite.combat.assistant.TradeDriver;
import com.utilitysuite.combat.reflex.ShieldDecision;
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
 *   <li>Other threats to us (arrows, blasts, a second attacker): the shield reflex stands down while this stage owns
 *   USE and reports them through {@link #threatFeed()}; the stage raises the same shield for them
 *   ({@link #guardStep}), so the key keeps one writer.</li>
 *   <li>The axe swap: the target drawing an axe (or a mob that disables shields) is acted on
 *   {@link ShieldDuel#REACTION_MARGIN} ticks after it shows while our shield is up ({@link #seesDisabler}), the same
 *   margin the raise uses; then the shield comes down and stays down against its melee until the axe is put away
 *   (believed after the same margin). A single big impact from the feed may still be blocked
 *   ({@link #worthADisable}).</li>
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

    /** Do not circle a shield that turns with us: wait outside its reach instead. */
    public static final String KEY_NO_CIRCLE = "shield_duel_no_circle";
    /** Centre-to-centre margin past its reach while waiting out a tracking shield (blocks). */
    static final double HOLD_MARGIN = 0.2;
    /** Half the width of a player's box: eye-to-box reach plus this is the centre distance on flat ground. */
    static final double HALF_WIDTH = 0.3;
    /** Spacing min assumed when no settings are attached (the setting's default). */
    static final double DEFAULT_SPACING_MIN = 2.7;

    private final ShieldFacts facts;
    private final ShieldDuel duel = new ShieldDuel();
    private final ShieldThreatFeed threats = new ShieldThreatFeed();
    /** Our shield is up against a fed threat. */
    private boolean guard;
    /** Ticks the guard has been up without a break (capped at {@link ShieldDuel#MAX_HOLD}), and the rest after. */
    private int guardHeld;
    private int guardRest;
    /** Ticks since the guard came down (0 on the lowering tick, -1 while up); large when it was never up. */
    private int sinceGuard = 1 << 20;

    /**
     * Expected damage of a single impact (arrow, blast) above which the shield is used with an axe in reach: more
     * than the axe's own hit (9 for a stone, iron or diamond axe), see {@link #worthADisable}.
     */
    static final float AXE_HIT_DAMAGE = 9f;

    // ---- the target's shield-disabling main hand as we have reacted to it (one target at a time) ----
    /** Target id the three fields below describe; -1 for none. */
    private int axeId = -1;
    /** What the facts said last tick. */
    private boolean axeRaw;
    /** What we act on: {@link #axeRaw} once the reaction margin has run. */
    private boolean axeSeen;
    /** Ticks until {@link #axeSeen} follows {@link #axeRaw}. */
    private int axeWait;

    private boolean enabled = true;
    private boolean blockEnabled = true;
    private boolean noCircle = true;
    private int waits;

    /**
     * Blocks to widen the spacing band so its low end sits {@value #HOLD_MARGIN} past the opponent's reach
     * ({@code eyeToBoxReach} + {@value #HALF_WIDTH} centre to centre on flat ground); 0 when the band already does.
     */
    public static double holdOutside(double eyeToBoxReach, double spacingMin) {
        return Math.max(0.0, eyeToBoxReach + HALF_WIDTH + HOLD_MARGIN - spacingMin);
    }

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
            clearGuard();
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
        d.oppDisables = seesDisabler(id, f.targetHoldsAxe() || s.targetHeavyHitter);
        d.oppInCone = ShieldDuel.inFrontCone(s.yaw, s.px, s.pz, s.tx, s.tz, ShieldDuel.FRONT_CONE_DEG);
        d.oppInReach = Math.sqrt(s.reachDistanceSq()) <= OPP_REACH + ShieldDuel.REACH_MARGIN;
        d.oppShieldUp = theirUse > 0 && !disabled;
        d.oppReadyIn = om.attackReadyIn(id, now);
        boolean up = d.step();
        boolean ownUp = guardStep(s, d, eating, theirUp) || up;
        if (eating) {
            holdingUse = false; // the sustain layer owns USE now and releases it when it is done
        } else if (ownUp) {
            ctx.hold(SimKey.USE, true); // cached in the assistant: repeated holds cost nothing
            holdingUse = true;
        } else {
            lowerOwn(ctx);
        }

        // ---- swing control ----
        boolean ownVeto = d.vetoSwing() || sinceGuard <= ShieldDuel.LOWER_GAP;
        if (ownVeto) {
            ctx.vetoSwing(); // attack clicks are eaten while our shield is up (Minecraft.handleKeybinds)
            ctx.vetoJump();
        }
        if (theirUp) {
            ctx.vetoSwing();
            ctx.vetoJump();
            if (noCircle && d.tracksUs()) {
                // it turns as fast as we circle: no open side to find. Wait just outside its reach (it cannot drop
                // the shield and swing from there) and punish the tick it lowers it
                CombatSettings cs = ctx.settings();
                double extra = holdOutside(om.learnedReach(id, OPP_REACH),
                        cs == null ? DEFAULT_SPACING_MIN : cs.spacingMin);
                if (extra > 0.0) {
                    ctx.widenSpacing(extra);
                }
                waits++;
            } else {
                lastSide = ShieldDuel.openSide(theirYaw, s.tx, s.tz, s.px, s.pz, s.hazardLeft, s.hazardRight,
                        lastSide);
                ctx.forceSide(lastSide);
                if (noCircle) {
                    d.noteCircling(ShieldDuel.offAxisDeg(theirYaw, s.tx, s.tz, s.px, s.pz));
                }
            }
            vetoes++;
        } else if (theirUpBefore && !ownVeto && SwingGate.ready(s.attackStrength) && s.inReach()
                && s.crosshairOnTarget) {
            // they lowered it (to swing, or it was disabled): our charged hit goes in on this tick
            ctx.tapAttack();
            ctx.vetoSwing(); // no second tap from the hit planner
            drops++;
        }
        if (!theirUp) {
            d.endCircling();
        }
        theirUpBefore = theirUp;

        if (disabled) {
            ctx.hitPolicy(HitPlanner.Policy.CRITS);
            ctx.pressAdvantage();
        }
        TradeDriver.shieldFeed().set(now, ownUp && !eating, theirUp);
        return false;
    }

    /**
     * One tick of the guard against what the shield reflex reports ({@link ShieldThreatFeed}): the reflex's own rule
     * ({@link ShieldDecision}: front cone only, raised {@link ShieldDecision#RAISE_TICKS} plus a reaction margin
     * before the impact), never turning, never into an axe in reach. An arrow or a blast is blocked until it has
     * landed; an attacker standing in reach is blocked between our swings (lowered for a charged swing at the
     * target). The duel's hold cap applies, so a threat that never lands does not pin us behind the shield.
     *
     * @return the shield should be up for such a threat this tick
     */
    private boolean guardStep(CombatSnapshot s, ShieldDuel d, boolean eating, boolean theirUp) {
        boolean up = false;
        if (guardRest > 0) {
            guardRest--;
        }
        // one take per step keeps the feed's freshness count honest, whatever the gates below say
        if (threats.take() && guardRest == 0) {
            boolean can = blockEnabled && d.hasShield && !eating
                    && (!(d.oppDisables && d.oppInReach) || worthADisable(s));
            // a single impact is blocked through; an attacker standing in reach is blocked between our swings (and
            // there is no swing to lower for while the target's own shield would eat it)
            boolean wantSwing = threats.standing() && !theirUp && SwingGate.ready(s.attackStrength) && s.inReach()
                    && s.crosshairOnTarget;
            ShieldDecision.Act act = ShieldDecision.decide(can, guard, threats.ticksToImpact(), threats.damage(),
                    threats.relYawDeg(), threats.frontConeDeg(), false, wantSwing);
            up = act == ShieldDecision.Act.RAISE || act == ShieldDecision.Act.HOLD;
        }
        if (up && guardHeld >= ShieldDuel.MAX_HOLD) {
            up = false;
            guardRest = ShieldDuel.REST_TICKS;
        }
        if (up) {
            guardHeld++;
            sinceGuard = -1;
        } else {
            guardHeld = 0;
            if (sinceGuard <= ShieldDuel.LOWER_GAP) {
                sinceGuard++; // 0 on the lowering tick, as ShieldDuel counts its own
            }
        }
        guard = up;
        return up;
    }

    /**
     * The target's shield-disabling main hand as we act on it, from this tick's fact. A change is believed
     * {@link ShieldDuel#REACTION_MARGIN} ticks after it shows (a person's reaction, the margin the raise lead already
     * carries), so the shield is not dropped on the very tick of the swap and not raised again on the tick the axe is
     * put away (a quick axe-sword-axe flick never baits it up). An axe that shows while our shield is down is believed
     * at once: nothing visible happens, the shield just stays down. State is per target id; a new id starts from "no
     * axe seen" and goes through the same rule.
     */
    private boolean seesDisabler(int id, boolean now) {
        if (id != axeId) {
            axeId = id;
            axeRaw = false;
            axeSeen = false;
            axeWait = 0;
        }
        if (now != axeRaw) {
            axeRaw = now;
            axeWait = now && !holdingUse ? 0 : ShieldDuel.REACTION_MARGIN;
        } else if (axeWait > 0) {
            axeWait--;
        }
        if (axeWait == 0) {
            axeSeen = axeRaw;
        }
        return axeSeen;
    }

    /**
     * With an axe in reach, is the fed threat worth the shield? Holding it there costs the shield for
     * {@link ShieldDuel#DISABLE_TICKS} ticks on the axe's next hit, and that is what the swap is for: disable, draw
     * the sword again, and hit for five seconds with nothing to block with. So the shield is spent only on a single
     * impact (an arrow, a blast; never an attacker that stays in reach, which would pin it up for the axe) that
     * <ul>
     *   <li>is the bigger hit: expected damage above the axe's own ({@value #AXE_HIT_DAMAGE}), or at least half of
     *   what health we have left, and</li>
     *   <li>the shield can still stop: it is up already, or the impact is at least the raise delay away (a raise that
     *   cannot block in time would only hand the axe a free disable).</li>
     * </ul>
     * An ordinary arrow is taken instead: it costs less than the shield does. The hold ends when the impact has
     * landed (the feed goes quiet), and the usual hold cap applies.
     */
    private boolean worthADisable(CombatSnapshot s) {
        if (threats.standing()) {
            return false;
        }
        float dmg = threats.damage();
        float left = s.health + s.absorption;
        boolean big = dmg > AXE_HIT_DAMAGE || (left > 0f && dmg >= left * 0.5f);
        return big && (holdingUse || threats.ticksToImpact() >= ShieldDuel.RAISE_DELAY);
    }

    private void lowerOwn(FightContext ctx) {
        if (holdingUse) {
            holdingUse = false;
            if (!ctx.eating()) {
                ctx.hold(SimKey.USE, false);
            }
        }
    }

    private void clearGuard() {
        guard = false;
        guardHeld = 0;
        guardRest = 0;
        sinceGuard = 1 << 20;
    }

    /** Our shield is this stage's while "Block with shield" is on: the shield reflex stands down for the fight. */
    @Override
    public boolean ownsKey(SimKey key) {
        return key == SimKey.USE && enabled && blockEnabled;
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
        clearGuard();
        axeId = -1;
        axeRaw = false;
        axeSeen = false;
        axeWait = 0;
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
        out.add(SettingSpec.bool(KEY_NO_CIRCLE, "Wait out a shield that turns with you", true, GROUP,
                "If circling never gets round a raised shield (the opponent turns as fast as you move), stop circling:"
                        + " wait just outside its reach and hit the moment it comes down."));
    }

    @Override
    public void read(ProfileConfig profile, String moduleId) {
        enabled = profile.getBool(moduleId, KEY_DUEL, true);
        blockEnabled = profile.getBool(moduleId, KEY_BLOCK, true);
        noCircle = profile.getBool(moduleId, KEY_NO_CIRCLE, true);
    }

    /** Settings without a profile (tests): the no-circle rule. */
    public void configureNoCircle(boolean on) {
        this.noCircle = on;
    }

    /** Ticks spent waiting outside the reach of a shield that turns with us. */
    public int waits() {
        return waits;
    }

    /** The current fight's opponent turns its shield with us. */
    public boolean tracksUs() {
        return duel.tracksUs();
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

    /** The shield is up against a threat from {@link #threatFeed()} (not the target's own swing). */
    public boolean guarding() {
        return guard;
    }

    /**
     * Where the shield reflex reports arrows, blasts and other attackers while this stage owns USE
     * ({@link #ownsKey}); the stage blocks them with the same key it already runs.
     */
    public ShieldThreatFeed threatFeed() {
        return threats;
    }

    /** The target's axe (or a shield-disabling mob) has been seen and is acted on: no shield against its melee. */
    public boolean disablerSeen() {
        return axeSeen;
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
