package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.assistant.SustainExtras;
import com.utilitysuite.combat.assistant.SustainPlanner;
import com.utilitysuite.combat.tactics.HealWindow;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;

/**
 * Healing in windows ({@link Slot#SUSTAIN}): heal when it is safe, and decide in hits left.
 * <ul>
 *   <li>Trigger: effective health at or below {@code heal_hits_left} of the opponent's measured hits (the fixed
 *       low-health threshold until a hit is measured).</li>
 *   <li>Window ({@link HealWindow#safeTicks}): eat only when the opponent cannot hit for most of the eat: knocked away,
 *       on a long cooldown, out of sight or far.</li>
 *   <li>No window: keep fighting for a knockback hit while ours is charged and in reach, else make distance along the
 *       flee route around cover; eat once the window opens; the built-in loop resumes the fight by itself.</li>
 *   <li>Top-ups: with the opponent away and hunger below 18, eat hotbar food that wastes nothing.</li>
 * </ul>
 * The built-in emergency (lethal imminent, totem, the low-health golden apple) is left exactly as it is: whenever
 * {@link SustainPlanner#decide} asks for anything, this stage steps aside, so it only ever acts earlier and safer.
 * The eat itself is the built-in sustain layer's ({@link FightContext#startEating}: it backs off facing the target,
 * finishes and restores the weapon), so the stage needs "Emergency sustain" on.
 * <p>
 * {@link #step} is allocation free with a cheap early-out; facts are refreshed every {@value #FACTS_INTERVAL} ticks.
 */
public final class HealWindowStage implements FightStage {

    public static final String ID = "heal_windows";

    public static final String HEAL_WINDOWS = "heal_windows";
    public static final String HEAL_HITS_LEFT = "heal_hits_left";
    public static final String HEAL_TOP_UP = "heal_top_up";

    public static final double DEFAULT_HITS_LEFT = 2.5;
    public static final double MIN_HITS_LEFT = 1.5;
    public static final double MAX_HITS_LEFT = 4.0;

    /** Ticks between {@link HealFacts#refresh} calls. */
    public static final int FACTS_INTERVAL = 10;
    /** Health drops below this are not hits (a cactus touch, hunger damage). */
    public static final float MIN_HIT = 0.5f;
    /** Weight of the newest hit in the damage-per-hit average. */
    public static final double HIT_ALPHA = 0.4;

    private final HealFacts facts;

    private boolean on = true;
    private double hitsLeft = DEFAULT_HITS_LEFT;
    private boolean topUp = true;

    private float lastEff = -1f;
    private double perHit;
    private int engagedTicks;
    private int factsIn;
    private boolean stagePullWanted;
    private boolean staged;
    private HealWindow.Plan lastPlan;
    private int eatsStarted;
    private int topUps;
    private int lastSafe;

    public HealWindowStage(HealFacts facts) {
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
        if (!on) {
            return false;
        }
        CombatSettings cs = ctx.settings();
        if (!cs.sustain || ctx.eating()) {
            return false;
        }
        engagedTicks++;
        float eff = s.effectiveHealth();
        if (lastEff >= 0f && eff < lastEff - MIN_HIT) {
            double hit = lastEff - eff;
            perHit = perHit <= 0.0 ? hit : perHit + HIT_ALPHA * (hit - perHit);
        }
        lastEff = eff;
        if (--factsIn <= 0) {
            factsIn = FACTS_INTERVAL;
            facts.refresh();
        }
        lastPlan = null;
        // staging: the decision only (the assistant owns the click path; see the integration notes)
        stagePullWanted = SustainExtras.shouldStagePull(cs.wholeInventory, ctx.world().extrasAllowedHere(),
                s.gappleSlot, s.sustainInvSlot, false, s.screenOpen, engagedTicks, false, staged);
        if (s.screenOpen) {
            return false;
        }
        if (SustainPlanner.decide(s, cs.lowHealth, ctx.lastHitDamage(), cs.totems, cs.gapples)
                != SustainPlanner.Action.NONE) {
            return false; // the hard emergency owns this tick, exactly as before
        }
        int safe = safeTicks(s, ctx);
        lastSafe = safe;
        if (cs.gapples && s.gappleSlot >= 0 && !s.gappleOnCooldown
                && HealWindow.wantHeal(eff, s.health, s.maxHealth, perHit, hitsLeft, cs.lowHealth)) {
            HealWindow.Plan plan = HealWindow.plan(safe, HealWindow.EAT_TICKS, s.inReach(), s.attackStrength);
            lastPlan = plan;
            switch (plan) {
                case EAT -> {
                    ctx.startEating(s, s.gappleSlot);
                    eatsStarted++;
                    return false; // the built-in sustain layer carries the eat on this same tick
                }
                case FIGHT_FOR_KNOCKBACK -> {
                    return false; // the built-in loop swings: a sprint hit sends it away and opens the window
                }
                default -> {
                    return ctx.retreatRoute(s); // around cover; without a route the fight goes on
                }
            }
        }
        if (topUp) {
            int slot = facts.bestFoodHotbarSlot();
            if (slot >= 0 && HealWindow.topUp(facts.hunger(), facts.bestFoodNutrition(), safe,
                    facts.bestFoodEatTicks())) {
                ctx.startEating(s, slot);
                topUps++;
                factsIn = 0; // re-read hunger after this eat
            }
        }
        return false;
    }

    /** Safe ticks from the snapshot and the opponent model. */
    static int safeTicks(CombatSnapshot s, FightContext ctx) {
        double dx = s.tx - s.px;
        double dz = s.tz - s.pz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double sep = 0.0;
        if (dist > 1.0e-6) {
            sep = (s.tvx * dx + s.tvz * dz) / dist;
        }
        int knock = HealWindow.knockTicksLeft(s.targetHurtTime, s.targetOnGround);
        if (knock == 0 && sep > 0.0) {
            sep = 0.0; // moving away on its own: it can turn back at once (only a hit's knockback coasts)
        }
        int ready = ctx.opponents().attackReadyIn(ctx.targetId(), ctx.clock());
        double approach = ctx.targetIsPlayer() ? HealWindow.PLAYER_APPROACH : HealWindow.MOB_APPROACH;
        return HealWindow.safeTicks(dist, sep, knock, 3.0, approach, ready, s.lineOfSight);
    }

    @Override
    public void onEngage(FightContext ctx) {
        engagedTicks = 0;
        staged = false;
        stagePullWanted = false;
        lastEff = -1f;
        perHit = 0.0;
        factsIn = 0;
        lastPlan = null;
    }

    @Override
    public void reset() {
        onEngage(null);
        lastSafe = 0;
    }

    @Override
    public void specs(List<SettingSpec> out) {
        out.add(SettingSpec.bool(HEAL_WINDOWS, "Heal in windows", true, "Emergency",
                "Heal in hits left and only when the opponent cannot hit for most of the eat; make a window"
                        + " (knockback hit, route around cover) when there is none. Needs Emergency sustain."));
        out.add(SettingSpec.decimal(HEAL_HITS_LEFT, "Heal at hits left", DEFAULT_HITS_LEFT, MIN_HITS_LEFT,
                MAX_HITS_LEFT, "Emergency",
                "Heal when this many of the opponent's measured hits would take you out.").asAdvanced());
        out.add(SettingSpec.bool(HEAL_TOP_UP, "Top up hunger between exchanges", true, "Emergency",
                "With the opponent away, eat hotbar food that wastes nothing while hunger is below 18.")
                .asAdvanced());
    }

    @Override
    public void read(ProfileConfig profile, String moduleId) {
        on = profile.getBool(moduleId, HEAL_WINDOWS, true);
        double k = profile.getDouble(moduleId, HEAL_HITS_LEFT, DEFAULT_HITS_LEFT);
        hitsLeft = Math.max(MIN_HITS_LEFT, Math.min(MAX_HITS_LEFT, k));
        topUp = profile.getBool(moduleId, HEAL_TOP_UP, true);
    }

    // ---- diagnostics and tests ------------------------------------------------------------------------------------

    /** Smoothed damage per hit taken this engagement (0 = none measured). */
    public double damagePerHit() {
        return perHit;
    }

    /** The staged pull is wanted this tick (the assistant's click path does it once the seam exists). */
    public boolean stagePullWanted() {
        return stagePullWanted;
    }

    /** The assistant reports the staged pull went out (seam request): no second pull this engagement. */
    public void notePulled() {
        staged = true;
        stagePullWanted = false;
    }

    /** The plan of the last tick that wanted a heal, or null. */
    public HealWindow.Plan lastPlan() {
        return lastPlan;
    }

    public int lastSafeTicks() {
        return lastSafe;
    }

    public int eatsStarted() {
        return eatsStarted;
    }

    public int topUps() {
        return topUps;
    }

    public boolean enabled() {
        return on;
    }
}
