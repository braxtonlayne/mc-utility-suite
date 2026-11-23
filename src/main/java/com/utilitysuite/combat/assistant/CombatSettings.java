package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;

/**
 * Settings of the {@link CombatAssistant}, read from the active profile under the module id. Plain mutable fields so
 * the per-tick code reads them without lookups. Everything past the two ranges sits in the collapsed Advanced drawer.
 */
public final class CombatSettings {

    public enum SprintReset { W_RELEASE, S_TAP, OFF }

    public static final String DISENGAGE_RADIUS = "disengage_radius";
    public static final String ALLY_DEFENSE_RANGE = "ally_defense_range";
    public static final String LOS_TIMEOUT_TICKS = "los_timeout_ticks";
    public static final String AIM_SPEED = "aim_speed";
    public static final String VELOCITY_LEAD = "velocity_lead";
    public static final String LEAD_TICKS = "lead_ticks";
    public static final String CRIT_SYNC = "crit_sync";
    public static final String SPACING_MIN = "spacing_min";
    public static final String SPACING_MAX = "spacing_max";
    public static final String STRAFE = "strafe";
    public static final String SPRINT_RESET = "sprint_reset";
    public static final String SHIELD_BREAK = "shield_break";
    public static final String MACE_SWAP = "mace_swap";
    public static final String WIND_CHARGE = "wind_charge_launch";
    public static final String SUSTAIN = "sustain";
    public static final String LOW_HEALTH = "low_health";
    public static final String TOTEMS = "totems";
    public static final String GAPPLES = "golden_apples";
    public static final String WHOLE_INVENTORY = "whole_inventory_consumables";
    public static final String SWAP_BACK = "swap_back_pulled";
    public static final String KEEP_TOTEM = "keep_offhand_totem";
    public static final String JUMP_RESET = "jump_reset";

    public static final String ENGAGE_MODE = "engage_mode";
    public static final String TARGETS = "proactive_targets";
    public static final String PRIORITY = "target_priority";
    public static final String ENGAGE_RANGE = "engage_range";
    public static final String ENGAGE_REQUIRES_LOS = "engage_requires_los";
    public static final String HOLD_GAP = "tactic_hold_gap";
    public static final String FOOD_OBJECTIVE = "food_objective";
    public static final String FOOD_RANGE = "food_range";
    public static final String EDGE_KNOCK = "tactic_edge_knock";
    public static final String TACTIC_UTILITY = "tactic_utility";
    public static final String AUTO_WEAPON = "auto_weapon";
    public static final String HIT_TRADE = "hit_trade";
    public static final String HIT_TRADE_HORIZON = "hit_trade_horizon";
    public static final String COOLDOWN_READ = "cooldown_read";
    public static final String ROUTE_PURSUIT = "route_pursuit";
    public static final String COMBO_KEEPER = "combo_keeper";
    public static final String ANTICIPATORY_AIM = "anticipatory_aim";
    public static final String STRAFE_WITH = "strafe_with";
    public static final String ARENA_READ = "arena_read";
    public static final String FEINTS = "feints";
    public static final String RANK_DIAL = "rank_dial";
    public static final String PEARL_INTERCEPT = "pearl_intercept";
    public static final String HIT_CHOICE = "hit_choice";
    public static final String DUEL_MODE = "duel_mode";
    public static final String EXACT_WORLD = "exact_world";
    public static final String CHASE_RETREAT = "chase_retreat";
    public static final String CRIT_NEEDS_HEALER = "crit_only_vs_healers";
    public static final String LEARN_THREAT = "learn_opponent_reach";
    public static final String KEEP_TEMPO = "keep_tempo";

    public TargetSelector.Mode engageMode = TargetSelector.Mode.REACTIVE;
    public TargetSelector.Targets targets = TargetSelector.Targets.HOSTILE;
    public TargetSelector.Priority priority = TargetSelector.Priority.THREAT;
    public double engageRange = 8.0;
    public boolean holdGap = true;
    public boolean foodObjective = true;
    public double foodRange = 12.0;
    public boolean edgeKnock = true;
    public boolean tacticUtility = true;
    public boolean autoWeapon = true;
    public boolean engageRequiresLos = true;

    public double disengageRadius = 16.0;
    public double allyDefenseRange = 12.0;
    public int losTimeoutTicks = 100;
    public float aimSpeed = 1.0f;
    public boolean velocityLead = true;
    public double leadTicks = 2.0;
    public boolean critSync = true;
    public double spacingMin = 2.7;
    public double spacingMax = 3.1;
    public boolean strafe = true;
    public SprintReset sprintReset = SprintReset.W_RELEASE;
    public boolean shieldBreak = true;
    public boolean maceSwap = false;
    public boolean windCharge = false;
    public boolean sustain = true;
    public float lowHealth = 8f;
    public boolean totems = true;
    public boolean gapples = true;
    public boolean wholeInventory = true;
    public boolean swapBack = true;
    public boolean keepTotem = true;
    public boolean jumpReset = false;
    public boolean hitTrade = true;
    public int hitTradeHorizon = 7;
    public boolean cooldownRead = true;
    public boolean routePursuit = true;
    public boolean comboKeeper = true;
    public boolean anticipatoryAim = true;
    public boolean strafeWith = true;
    public boolean arenaRead = true;
    public boolean feints = true;
    public boolean rankDial = false;
    public boolean pearlIntercept = true;
    /** Kind of hit to throw: AUTO picks per hit (sprint knockback, crit, sweep); the others force one. */
    public HitPlanner.Policy hitChoice = HitPlanner.Policy.AUTO;
    /** Never disengage: fleeing becomes a reset, range and sight never end the fight while the target is loaded. */
    public boolean duelMode = false;
    /**
     * Combat and pursuit predictions read the exact block shapes Movement Assist uses (slabs, stairs, fence tops,
     * step-up, ice) and both fighters' speed, Jump Boost and sprint state, instead of full cubes and a plain body.
     * Off by default until it is A/B-tested in the arena.
     */
    public boolean exactWorld = false;
    /** A retreating opponent (facing away, running, not swinging) is no threat: FIGHT, step in, chase from reach. */
    public boolean chaseRetreat = true;
    /** Crit-or-nothing only against an opponent whose healing outpaces our plain hits; otherwise swing every charge. */
    public boolean critNeedsHealer = true;
    /** Learn the opponent's reach and post-hit recovery from the fight and plan the spacing on them. */
    public boolean learnThreat = true;
    /** No kiting on reaction hops, no regeneration reset against an opponent that keeps pace. */
    public boolean keepTempo = true;

    public void read(ProfileConfig p, String id) {
        engageMode = parseEnum(p.getString(id, ENGAGE_MODE, ""), TargetSelector.Mode.REACTIVE);
        targets = parseEnum(p.getString(id, TARGETS, ""), TargetSelector.Targets.HOSTILE);
        priority = parseEnum(p.getString(id, PRIORITY, ""), TargetSelector.Priority.THREAT);
        engageRequiresLos = p.getBool(id, ENGAGE_REQUIRES_LOS, true);
        holdGap = p.getBool(id, HOLD_GAP, true);
        foodObjective = p.getBool(id, FOOD_OBJECTIVE, true);
        edgeKnock = p.getBool(id, EDGE_KNOCK, true);
        tacticUtility = p.getBool(id, TACTIC_UTILITY, true);
        autoWeapon = p.getBool(id, AUTO_WEAPON, true);
        wholeInventory = p.getBool(id, WHOLE_INVENTORY, true);
        swapBack = p.getBool(id, SWAP_BACK, true);
        keepTotem = p.getBool(id, KEEP_TOTEM, true);
        jumpReset = p.getBool(id, JUMP_RESET, false);
        disengageRadius = p.getDouble(id, DISENGAGE_RADIUS, 16.0);
        engageRange = Math.min(disengageRadius - 1.0, p.getDouble(id, ENGAGE_RANGE, 8.0));
        foodRange = Math.min(disengageRadius - 1.0, p.getDouble(id, FOOD_RANGE, 12.0));
        allyDefenseRange = p.getDouble(id, ALLY_DEFENSE_RANGE, 12.0);
        losTimeoutTicks = p.getInt(id, LOS_TIMEOUT_TICKS, 100);
        aimSpeed = (float) p.getDouble(id, AIM_SPEED, 1.0);
        velocityLead = p.getBool(id, VELOCITY_LEAD, true);
        leadTicks = p.getDouble(id, LEAD_TICKS, 2.0);
        critSync = p.getBool(id, CRIT_SYNC, true);
        spacingMin = p.getDouble(id, SPACING_MIN, 2.7);
        spacingMax = Math.max(spacingMin + 0.1, p.getDouble(id, SPACING_MAX, 3.1));
        strafe = p.getBool(id, STRAFE, true);
        sprintReset = parse(p.getString(id, SPRINT_RESET, SprintReset.W_RELEASE.name()));
        shieldBreak = p.getBool(id, SHIELD_BREAK, true);
        maceSwap = p.getBool(id, MACE_SWAP, false);
        windCharge = p.getBool(id, WIND_CHARGE, false);
        sustain = p.getBool(id, SUSTAIN, true);
        lowHealth = (float) p.getDouble(id, LOW_HEALTH, 8.0);
        totems = p.getBool(id, TOTEMS, true);
        gapples = p.getBool(id, GAPPLES, true);
        hitTrade = p.getBool(id, HIT_TRADE, true);
        hitTradeHorizon = Math.max(6, Math.min(8, p.getInt(id, HIT_TRADE_HORIZON, 7)));
        cooldownRead = p.getBool(id, COOLDOWN_READ, true);
        routePursuit = p.getBool(id, ROUTE_PURSUIT, true);
        comboKeeper = p.getBool(id, COMBO_KEEPER, true);
        anticipatoryAim = p.getBool(id, ANTICIPATORY_AIM, true);
        strafeWith = p.getBool(id, STRAFE_WITH, true);
        arenaRead = p.getBool(id, ARENA_READ, true);
        feints = p.getBool(id, FEINTS, true);
        rankDial = p.getBool(id, RANK_DIAL, false);
        pearlIntercept = p.getBool(id, PEARL_INTERCEPT, true);
        hitChoice = parseEnum(p.getString(id, HIT_CHOICE, ""), HitPlanner.Policy.AUTO);
        duelMode = p.getBool(id, DUEL_MODE, false);
        exactWorld = p.getBool(id, EXACT_WORLD, false);
        chaseRetreat = p.getBool(id, CHASE_RETREAT, true);
        critNeedsHealer = p.getBool(id, CRIT_NEEDS_HEALER, true);
        learnThreat = p.getBool(id, LEARN_THREAT, true);
        keepTempo = p.getBool(id, KEEP_TEMPO, true);
    }

    static <E extends Enum<E>> E parseEnum(String s, E def) {
        for (E e : def.getDeclaringClass().getEnumConstants()) {
            if (e.name().equalsIgnoreCase(s)) {
                return e;
            }
        }
        return def;
    }

    private static SprintReset parse(String s) {
        for (SprintReset r : SprintReset.values()) {
            if (r.name().equalsIgnoreCase(s)) {
                return r;
            }
        }
        return SprintReset.W_RELEASE;
    }

    public static List<SettingSpec> specs() {
        return List.of(
                SettingSpec.choice(ENGAGE_MODE, "Engagement", TargetSelector.Mode.REACTIVE, "",
                        "Reactive: fight back only when you or a defended ally is hit, or you land a hit. Proactive:"
                                + " also go after valid targets in range on its own (single-player or allow-listed"
                                + " servers only)."),
                SettingSpec.choice(TARGETS, "Proactive targets", TargetSelector.Targets.HOSTILE, "Proactive",
                        "What proactive mode goes after. Allies on the roster are never targeted; neutral mobs only"
                                + " once they are angry."),
                SettingSpec.choice(PRIORITY, "Target priority", TargetSelector.Priority.THREAT, "Proactive",
                        "Threat: whoever just hit you, swelling creepers, aggressive and ranged mobs first."
                                + " Closest or lowest health otherwise."),
                SettingSpec.decimal(ENGAGE_RANGE, "Engage range", 8.0, 3.0, 24.0, "Proactive",
                        "Proactive mode starts a fight only within this many blocks (kept below the disengage"
                                + " radius)."),
                SettingSpec.bool(ENGAGE_REQUIRES_LOS, "Require line of sight", true, "Proactive",
                        "Only go after targets you can see. Off lets it pick targets behind walls (less legit)."),
                SettingSpec.bool(FOOD_OBJECTIVE, "Hunt for food when out", true, "Proactive",
                        "When you have no food and none in nearby chests the assistant remembers, hunt nearby cows,"
                                + " pigs, sheep, chickens and rabbits and pick up the drops, but only while you are not"
                                + " moving and no hostile is around. Named or leashed animals are left alone."),
                SettingSpec.decimal(FOOD_RANGE, "Food range", 12.0, 4.0, 24.0, "Proactive",
                        "How far the food objective looks for animals."),
                SettingSpec.bool(HOLD_GAP, "Gap ambush", true, "Tactics",
                        "Never chase into a ravine or pit. Wait at the edge and hit the target while it jumps across, so"
                                + " the knockback drops it."),
                SettingSpec.bool(EDGE_KNOCK, "Edge knock", true, "Tactics",
                        "When the target stands near a drop or lava, circle to the far side and sprint-hit it toward"
                                + " the edge."),
                SettingSpec.bool(ROUTE_PURSUIT, "Run routes when chasing or fleeing", true, "Tactics",
                        "Chase a target that got far away or out of sight, and run from a fight you are losing, along"
                                + " a planned route like a person would (looking where you run) instead of walking"
                                + " straight at it or backing away. Falls back to the old moves when no route is"
                                + " available."),
                SettingSpec.bool(COMBO_KEEPER, "Keep combos going", true, "Tactics",
                        "After a hit, predict where the target's knockback lands it and time the sprint reset and the"
                                + " steps so the next hit comes the moment it can be hit again. Needs Sprint reset on."),
                SettingSpec.bool(ANTICIPATORY_AIM, "Aim where they strafe to", true, "Tactics",
                        "Learn the rhythm of the target's strafing and lead the aim to where it is about to be, not"
                                + " just along its current movement. Needs Lead moving targets on."),
                SettingSpec.bool(STRAFE_WITH, "Strafe with them", true, "Tactics",
                        "Circle the same way round as the target when its strafe is readable, so it stays near your"
                                + " crosshair. Never overrides a better step from the trade solver."),
                SettingSpec.bool(ARENA_READ, "Read the arena", true, "Tactics",
                        "Scan the ground around the fight a few columns per tick: knock the target toward lava, drops,"
                                + " cobwebs or walls, take nearby high ground, back under a low ceiling against a"
                                + " crit-hopper, and never hop where there is no room to jump."),
                SettingSpec.bool(FEINTS, "Feints against players", true, "Tactics",
                        "Against players only (never mobs): break your strafe pattern when they pre-aim it, and back"
                                + " off to bait a sprint-in before stepping back in on their cooldown."),
                SettingSpec.bool(RANK_DIAL, "Match the opponent's rank (one notch above)", false, "Tactics",
                        "Estimate the opponent's skill from the fight and set the shared aim and timing skill one"
                                + " notch above it, so you win like a slightly better player. Changes the skill every"
                                + " module uses while the fight lasts; a fixed skill in the settings still wins."),
                SettingSpec.bool(PEARL_INTERCEPT, "Chase ender pearls", true, "Tactics",
                        "When the target throws an ender pearl, run to where it will land instead of walking after"
                                + " it. Needs Run routes when chasing or fleeing."),
                SettingSpec.bool(AUTO_WEAPON, "Draw the best weapon", true, "Tactics",
                        "When a fight starts, switch to the hotbar weapon with the most damage per second."),
                SettingSpec.bool(TACTIC_UTILITY, "Read the fight", true, "Tactics",
                        "Weigh both sides' damage, health and healing: keep distance from a crit-hopper in an even"
                                + " trade, and back off when losing badly with nothing to heal."),
                SettingSpec.decimal(DISENGAGE_RADIUS, "Disengage radius", 16.0, 6.0, 48.0, "",
                        "Stop when the target gets farther than this many blocks."),
                SettingSpec.decimal(ALLY_DEFENSE_RANGE, "Ally defense range", 12.0, 2.0, 32.0, "",
                        "Defend roster players marked 'defend' only within this many blocks."),
                SettingSpec.integer(LOS_TIMEOUT_TICKS, "Line-of-sight timeout (ticks)", 100, 20, 400, "Aim",
                        "Disengage after the target has been out of sight this long.").asAdvanced(),
                SettingSpec.decimal(AIM_SPEED, "Aim speed", 1.0, 0.25, 3.0, "Aim",
                        "Multiplier on the smoother's camera speed.").asAdvanced(),
                SettingSpec.bool(VELOCITY_LEAD, "Lead moving targets", true, "Aim",
                        "Aim slightly ahead of a moving target.").asAdvanced(),
                SettingSpec.decimal(LEAD_TICKS, "Lead (ticks)", 2.0, 0.0, 6.0, "Aim",
                        "How far ahead to lead, in ticks of target movement.").asAdvanced(),
                SettingSpec.bool(CRIT_SYNC, "Critical-hit timing", true, "Cooldown",
                        "Hop and swing while falling for critical hits.").asAdvanced(),
                SettingSpec.choice(HIT_CHOICE, "Hit choice", HitPlanner.Policy.AUTO, "Hit choice",
                        "Auto: open and re-engage with a sprint knockback hit, keep combos with knockback, crit when"
                                + " knockback is worth little (cornered or eating target, out-damaged, a big mob), sweep"
                                + " only when two or more hostiles stand together and nothing else is in reach of the"
                                + " sweep. Knockback, Crits or Plain force one kind.").asAdvanced(),
                SettingSpec.bool(DUEL_MODE, "Never disengage (duel mode)", false, "Tactics",
                        "Never run from the fight or drop it when the target gets far or out of sight while it is"
                                + " alive and loaded: back off, heal and come back instead. For closed arenas and boss"
                                + " rooms."),
                SettingSpec.bool(CHASE_RETREAT, "Chase a retreating opponent", true, "Tactics",
                        "When the opponent turns away and runs without swinging, treat it as no threat: keep"
                                + " fighting, step into reach and chase it as soon as it leaves reach, steering it"
                                + " toward walls."),
                SettingSpec.bool(KEEP_TEMPO, "Keep the tempo", true, "Tactics",
                        "Don't keep extra distance because of hops that only answer your hits, and don't back off to"
                                + " regenerate from an opponent that runs as fast as you."),
                SettingSpec.bool(CRIT_NEEDS_HEALER, "Crits only against fast healers", true, "Hit choice",
                        "When out-geared, wait for crits only if the opponent heals faster than plain hits hurt it;"
                                + " otherwise hit on every charged swing in reach.").asAdvanced(),
                SettingSpec.bool(LEARN_THREAT, "Learn their reach and recovery", true, "Spacing",
                        "Measure how far the opponent's hits reach and how long it needs to answer your hit, then"
                                + " hover at the edge of its real reach and step in right after a landed hit.")
                        .asAdvanced(),
                SettingSpec.bool(COOLDOWN_READ, "Read their cooldown", true, "Cooldown",
                        "Watch the opponent's swings to know when their next hit is charged. Off treats them as"
                                + " always ready to hit.").asAdvanced(),
                SettingSpec.decimal(SPACING_MIN, "Spacing min (blocks)", 2.7, 1.5, 4.0, "Spacing",
                        "Back off when closer than this.").asAdvanced(),
                SettingSpec.decimal(SPACING_MAX, "Spacing max (blocks)", 3.1, 1.8, 4.5, "Spacing",
                        "Close in when farther than this.").asAdvanced(),
                SettingSpec.bool(STRAFE, "Strafe", true, "Spacing",
                        "Move sideways around the target, away from cliffs and lava.").asAdvanced(),
                SettingSpec.bool(HIT_TRADE, "Trade solver", true, "Spacing",
                        "Look a few ticks ahead for both of you and pick the movement keys that land more hits than"
                                + " you take: step in right after they swing, stay just out of reach while their hit is"
                                + " charged. Off uses the fixed spacing band.").asAdvanced(),
                SettingSpec.integer(HIT_TRADE_HORIZON, "Trade look-ahead (ticks)", 7, 6, 8, "Spacing",
                        "How many ticks ahead the trade solver simulates.").asAdvanced(),
                SettingSpec.bool(EXACT_WORLD, "Exact world shapes for combat prediction", false, "Spacing",
                        "The trade solver, the combo keeper and route chasing predict both of you on the real block"
                                + " shapes Movement Assist uses (slabs, stairs, fence tops, stepping up, ice) and with"
                                + " each player's speed, Jump Boost and sprint, instead of full cubes and a plain body."
                                + " Experimental: off until it is tested in the arena.").asAdvanced(),
                SettingSpec.choice(SPRINT_RESET, "Sprint reset", SprintReset.W_RELEASE, "Spacing",
                        "Brief forward release or back tap after a hit.").asAdvanced(),
                SettingSpec.bool(SHIELD_BREAK, "Axe vs. shields", true, "Shield & weapons",
                        "Swap to a hotbar axe to disable a raised shield, then back.").asAdvanced(),
                SettingSpec.bool(MACE_SWAP, "Mace when falling", false, "Shield & weapons",
                        "Swap to a hotbar mace for swings made while falling.").asAdvanced(),
                SettingSpec.bool(WIND_CHARGE, "Wind charge launch", false, "Shield & weapons",
                        "Launch with a wind charge before a mace swing.").asAdvanced(),
                SettingSpec.bool(SUSTAIN, "Emergency sustain", true, "Emergency",
                        "Back off, equip a totem or eat a golden apple at low health.").asAdvanced(),
                SettingSpec.decimal(LOW_HEALTH, "Low health (half-hearts)", 8.0, 2.0, 16.0, "Emergency",
                        "Health at which to back off or eat.").asAdvanced(),
                SettingSpec.bool(TOTEMS, "Totem to offhand", true, "Emergency",
                        "Move a totem to the offhand when lethal damage is imminent.").asAdvanced(),
                SettingSpec.bool(GAPPLES, "Golden apples", true, "Emergency",
                        "Eat a hotbar golden apple at low health.").asAdvanced(),
                SettingSpec.bool(WHOLE_INVENTORY, "Use items from whole inventory", true, "Emergency",
                        "Pull golden apples or healing/regeneration potions from the main inventory into the hotbar"
                                + " (single-player or allow-listed servers only).").asAdvanced(),
                SettingSpec.bool(SWAP_BACK, "Swap pulled items back", true, "Emergency",
                        "Return a pulled item to its inventory slot after use.").asAdvanced(),
                SettingSpec.bool(KEEP_TOTEM, "Keep a totem in offhand", true, "Emergency",
                        "While armed, move a totem to an empty offhand right away (single-player or allow-listed"
                                + " servers only).").asAdvanced(),
                SettingSpec.bool(JUMP_RESET, "Jump reset on knockback", false, "Spacing",
                        "Tap jump when hit while engaged and on the ground (single-player or allow-listed servers"
                                + " only).").asAdvanced());
    }
}
