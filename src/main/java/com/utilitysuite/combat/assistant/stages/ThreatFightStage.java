package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.assistant.StrafePlanner;
import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.combat.tactics.MobPlaybook;
import com.utilitysuite.combat.tactics.ThreatRank;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;

/**
 * Threat-aware fights against mob packs (upgrade pass 2.5), as a {@link Slot#PRE_AIM} stage that shapes the built-in
 * loop with one-tick requests and never takes the tick itself:
 * <ol>
 *   <li><b>Re-pick</b> while engaged, every {@link NearbyThreats#REFRESH_TICKS} ticks, by {@link ThreatRank} with
 *       hysteresis (a swelling creeper, a skeleton drawing at close range or a mob about to hit from behind outranks a
 *       zombie at half health). Only mobs already hostile to the player qualify (the reactive rule), never a
 *       never-target row (warden, wither), never without line of sight while sight is required, and never away from a
 *       player opponent (the threat answers below still run in a player fight).</li>
 *   <li><b>Fuse back-off</b>: while a creeper inside {@link MobPlaybook#BLAST_CLEAR} swells, the spacing band moves
 *       out past it (the assistant keeps facing the target and backs off without disengaging); back to normal as
 *       soon as it stops swelling or dies. A creeper that is not the target and stands behind: a forced strafe away
 *       from it instead, since backing off would walk into it.</li>
 *   <li><b>Projectile answer</b>: a bow drawn at the player or a projectile on its way: a forced strafe away from the
 *       shooter (and away from hazards) for {@link #DODGE_TICKS}, sticky so the dodge does not wobble. The reflex
 *       dodge stands down while the assistant holds movement; this is its replacement inside a fight.</li>
 *   <li><b>Pack handling</b>: {@link #PACK_SIZE} or more melee mobs in reach: the band widens so they string out and
 *       the hit choice is AUTO (the hit planner sweeps on its own when two stand together).</li>
 *   <li><b>Second attacker</b>: another hostile melee mob in reach: its position goes to the context and the
 *       strafe circles so the target stays between us and it (soft; hazards, forced sides, retreat and escape win).</li>
 *   <li><b>Mob playbook</b> ({@link MobPlaybook}) of the current target, every tick: its hit policy, extra spacing,
 *       and rule (no crit hops on a spider or a baby zombie, close a skeleton on a strafe, rush a witch).</li>
 * </ol>
 * {@link #step} is allocation free; the table refresh is budgeted inside {@link NearbyThreats}.
 */
public final class ThreatFightStage implements FightStage {

    public static final String KEY_THREAT_FIGHTS = "threat_fights";
    public static final String KEY_MOB_PLAYBOOK = "mob_playbook";

    /** Forced strafe after a bow draw or a projectile is seen (re-armed while it lasts). */
    public static final int DODGE_TICKS = 12;
    /** A bow drawn at us counts from this far. */
    public static final double DRAW_RANGE = 24.0;
    public static final int PACK_SIZE = 3;
    /** A second hostile melee mob this close (horizontal) steers the strafe to keep the target between us. */
    public static final double SECOND_RANGE = 4.5;
    /** Melee mobs inside this horizontal distance count toward a pack. */
    public static final double PACK_RANGE = 4.0;
    /** Extra blocks of spacing while a pack is on us. */
    public static final double PACK_EXTRA = 1.5;
    /** How long the last attacker counts as hostile without other signs (fight ticks). */
    public static final int ATTACKER_MEMORY = 200;

    private final NearbyThreats threats;
    private final ThreatRank rank = new ThreatRank();
    private boolean enabled = true;
    private boolean playbook = true;

    private long dodgeUntil = -1L;
    private int dodgeSide = StrafePlanner.NONE;
    private boolean backingOff;
    private boolean packMode;
    private int requests;
    private int dodges;

    public ThreatFightStage(NearbyThreats threats) {
        this.threats = threats;
    }

    @Override
    public String id() {
        return "threat_fights";
    }

    @Override
    public Slot slot() {
        return Slot.PRE_AIM;
    }

    @Override
    public boolean step(CombatSnapshot s, FightContext ctx) {
        backingOff = false;
        packMode = false;
        if (!enabled) {
            return false;
        }
        NearbyThreats t = threats;
        long clock = ctx.clock();
        boolean refreshed = t.update(clock);
        int target = ctx.targetId();
        int tRow = t.indexOf(target);

        // ---- (a) re-pick by threat (refresh ticks only; never away from a player opponent) ---------------------
        if (refreshed && !ctx.targetIsPlayer() && !ctx.eating()) {
            repick(s, ctx, t, target, tRow, clock);
        }

        // ---- the forward direction (toward the target) and its left, for sides and fronts --------------------
        double fx = s.tx - s.px;
        double fz = s.tz - s.pz;
        double fl = Math.sqrt(fx * fx + fz * fz);
        if (fl < 1.0e-6) {
            fx = 0.0;
            fz = 1.0;
        } else {
            fx /= fl;
            fz /= fl;
        }
        double lx = fz; // Minecraft's left of a facing (x east, z south): (fz, -fx)
        double lz = -fx;

        // ---- (b) fuse back-off --------------------------------------------------------------------------------
        int creeper = nearestSwelling(t);
        int awaySide = StrafePlanner.NONE;
        if (creeper >= 0) {
            backingOff = true;
            double rx = t.x[creeper] - s.px;
            double rz = t.z[creeper] - s.pz;
            if (t.ids[creeper] == target) {
                ctx.widenSpacing(Math.max(0.0, MobPlaybook.BLAST_CLEAR - ctx.settings().spacingMin));
            } else if (rx * fx + rz * fz >= 0.0) {
                ctx.widenSpacing(Math.max(0.0, MobPlaybook.BLAST_CLEAR - t.distance[creeper]));
            } else {
                awaySide = sideAway(rx * lx + rz * lz, s);
            }
        }

        // ---- (c) projectile answer ----------------------------------------------------------------------------
        int shooter = nearestDrawing(t, ctx, clock);
        boolean shot = shooter >= 0 || t.projectileIncoming;
        if (shot) {
            if (clock >= dodgeUntil) {
                dodges++;
                double rx = shooter >= 0 ? t.x[shooter] - s.px : t.projectileRx;
                double rz = shooter >= 0 ? t.z[shooter] - s.pz : t.projectileRz;
                dodgeSide = sideAway(rx * lx + rz * lz, s);
            }
            dodgeUntil = clock + DODGE_TICKS;
        }
        MobPlaybook.Row row = tRow >= 0 ? MobPlaybook.row(t.types[tRow], t.has(tRow, ThreatRank.BABY))
                : MobPlaybook.DEFAULT;
        boolean strafeIn = playbook && row.rule() == MobPlaybook.Rule.STRAFE_IN && !s.inReach();
        if ((clock < dodgeUntil || strafeIn) && !ctx.eating()) {
            if (dodgeSide == StrafePlanner.NONE || blocked(dodgeSide, s)) {
                dodgeSide = sideAway(0.0, s); // a free side (sticky until a hazard closes it)
            }
            if (dodgeSide != StrafePlanner.NONE) {
                ctx.forceSide(dodgeSide);
            }
        } else if (awaySide != StrafePlanner.NONE) {
            ctx.forceSide(awaySide);
        }

        // ---- (c2) second attacker: circle so the target stays between us and it --------------------------------
        if (!ctx.eating()) {
            int second = secondAttacker(t, ctx, clock, target);
            if (second >= 0) {
                ctx.secondThreat(t.x[second], t.z[second]);
            }
        }

        // ---- (d) pack handling --------------------------------------------------------------------------------
        boolean rush = playbook && row.rule() == MobPlaybook.Rule.RUSH;
        if (countPack(t, s, target, tRow) >= PACK_SIZE) {
            packMode = true;
            if (!rush) {
                ctx.widenSpacing(PACK_EXTRA);
            }
            ctx.hitPolicy(HitPlanner.Policy.AUTO);
        }

        // ---- playbook of the current target -------------------------------------------------------------------
        if (playbook && row != MobPlaybook.DEFAULT) {
            if (row.policy() != null) {
                ctx.hitPolicy(row.policy());
            }
            if (row.spacingExtra() > 0.0 && !rush) {
                ctx.widenSpacing(row.spacingExtra());
            }
            if (row.rule() == MobPlaybook.Rule.NO_HOPS) {
                ctx.vetoJump();
            }
        }
        return false;
    }

    private void repick(CombatSnapshot s, FightContext ctx, NearbyThreats t, int target, int tRow, long clock) {
        ThreatRank r = rank;
        r.begin(target, clock);
        boolean los = ctx.settings().engageRequiresLos;
        int attacker = ctx.lastAttackerId();
        boolean attackerFresh = attacker >= 0 && clock - ctx.lastAttackerAt() <= ATTACKER_MEMORY;
        for (int i = 0; i < t.size; i++) {
            int id = t.ids[i];
            int f = t.flags[i];
            if (id == target) {
                f |= ThreatRank.HOSTILE | ThreatRank.LOS; // engaged already: hostile by definition
            } else if (attackerFresh && id == attacker) {
                f |= ThreatRank.HOSTILE; // it hit us: already attacking, whatever its synced flags say
            }
            boolean never = MobPlaybook.row(t.types[i], false).neverTarget();
            double d = t.distance[i];
            r.offer(id, ThreatRank.score(f, d, t.health[i], never, los), ThreatRank.emergency(f, d));
        }
        if (tRow < 0) {
            r.currentScore(ThreatRank.score(ThreatRank.HOSTILE | ThreatRank.LOS, s.horizontalDistance(),
                    s.targetHealth, false, false));
        }
        int next = r.decide(clock);
        if (next >= 0) {
            requests++;
            ctx.requestTarget(next);
        }
    }

    private static int nearestSwelling(NearbyThreats t) {
        int best = -1;
        for (int i = 0; i < t.size; i++) {
            if (t.has(i, ThreatRank.SWELLING) && t.distance[i] <= MobPlaybook.BLAST_CLEAR
                    && (best < 0 || t.distance[i] < t.distance[best])) {
                best = i;
            }
        }
        return best;
    }

    private static int nearestDrawing(NearbyThreats t, FightContext ctx, long clock) {
        int attacker = ctx.lastAttackerId();
        boolean attackerFresh = attacker >= 0 && clock - ctx.lastAttackerAt() <= ATTACKER_MEMORY;
        int best = -1;
        for (int i = 0; i < t.size; i++) {
            if (!t.has(i, ThreatRank.DRAWING) || t.distance[i] > DRAW_RANGE) {
                continue;
            }
            if (!t.has(i, ThreatRank.HOSTILE) && !(attackerFresh && t.ids[i] == attacker)) {
                continue;
            }
            if (best < 0 || t.distance[i] < t.distance[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * The nearest hostile melee mob other than the target inside {@link #SECOND_RANGE} (a swelling creeper is the
     * fuse answer's business), or -1.
     */
    private static int secondAttacker(NearbyThreats t, FightContext ctx, long clock, int target) {
        int attacker = ctx.lastAttackerId();
        boolean attackerFresh = attacker >= 0 && clock - ctx.lastAttackerAt() <= ATTACKER_MEMORY;
        int best = -1;
        for (int i = 0; i < t.size; i++) {
            if (t.ids[i] == target || t.distance[i] > SECOND_RANGE || t.has(i, ThreatRank.RANGED)
                    || t.has(i, ThreatRank.SWELLING)) {
                continue;
            }
            if (!t.has(i, ThreatRank.HOSTILE) && !(attackerFresh && t.ids[i] == attacker)) {
                continue; // the reactive rule: only a mob already attacking us
            }
            if (best < 0 || t.distance[i] < t.distance[best]) {
                best = i;
            }
        }
        return best;
    }

    /** Melee mobs already hostile within {@link #PACK_RANGE}, the target included. */
    private static int countPack(NearbyThreats t, CombatSnapshot s, int target, int tRow) {
        int n = tRow < 0 && s.horizontalDistance() <= PACK_RANGE ? 1 : 0;
        for (int i = 0; i < t.size; i++) {
            boolean hostile = t.has(i, ThreatRank.HOSTILE) || t.ids[i] == target;
            if (hostile && !t.has(i, ThreatRank.RANGED) && !t.has(i, ThreatRank.SWELLING)
                    && t.distance[i] <= PACK_RANGE) {
                n++;
            }
        }
        return n;
    }

    /**
     * The strafe side away from a source whose left component (dot with the facing's left) is {@code leftDot}, or
     * the free side when the source is straight ahead or behind; the other side when the first is a hazard; NONE
     * when both are.
     */
    private int sideAway(double leftDot, CombatSnapshot s) {
        int want;
        if (leftDot > 0.05) {
            want = StrafePlanner.RIGHT;
        } else if (leftDot < -0.05) {
            want = StrafePlanner.LEFT;
        } else {
            want = dodgeSide != StrafePlanner.NONE ? dodgeSide : StrafePlanner.RIGHT;
        }
        if (blocked(want, s)) {
            want = -want;
        }
        return blocked(want, s) ? StrafePlanner.NONE : want;
    }

    private static boolean blocked(int side, CombatSnapshot s) {
        return side == StrafePlanner.LEFT ? s.hazardLeft : side == StrafePlanner.RIGHT && s.hazardRight;
    }

    @Override
    public void onEngage(FightContext ctx) {
        // the rank keeps its dwell clock across its own switch; the dodge side carries over
    }

    @Override
    public void reset() {
        threats.reset();
        rank.reset();
        dodgeUntil = -1L;
        dodgeSide = StrafePlanner.NONE;
        backingOff = false;
        packMode = false;
    }

    @Override
    public void specs(List<SettingSpec> out) {
        out.add(SettingSpec.bool(KEY_THREAT_FIGHTS, "Threat-aware fights", true, "Tactics",
                "While fighting mobs: switch to the biggest threat (a swelling creeper, a skeleton drawing at you, a "
                        + "mob about to hit from behind), back out of a creeper's blast while it swells, strafe out "
                        + "of a drawn bow's line, and spread a pack out. Only mobs already attacking you count."));
        out.add(SettingSpec.bool(KEY_MOB_PLAYBOOK, "Mob playbook", true, "Tactics",
                "Fight each mob the way a pro does: sprint hits on creepers, no crit hops into a spider's leap or "
                        + "onto a baby zombie, close on skeletons with a strafe, rush witches. Unknown and modded "
                        + "mobs get the normal fight."));
    }

    @Override
    public void read(ProfileConfig profile, String moduleId) {
        enabled = profile.getBool(moduleId, KEY_THREAT_FIGHTS, true);
        playbook = profile.getBool(moduleId, KEY_MOB_PLAYBOOK, true);
    }

    // ---- diagnostics (tests) ---------------------------------------------------------------------------------------

    /** Target switches requested. */
    public int requests() {
        return requests;
    }

    /** Dodge windows started. */
    public int dodges() {
        return dodges;
    }

    /** A swelling creeper was inside the blast range on the last step. */
    public boolean backingOff() {
        return backingOff;
    }

    /** A pack was on the player on the last step. */
    public boolean packMode() {
        return packMode;
    }

    public ThreatRank rank() {
        return rank;
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    public void setPlaybook(boolean on) {
        playbook = on;
    }
}
