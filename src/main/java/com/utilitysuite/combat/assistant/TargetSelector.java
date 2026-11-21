package com.utilitysuite.combat.assistant;

/**
 * Picks the target for the proactive engagement mode from a {@link TargetCandidates} scan. Pure and allocation free.
 * <ul>
 *   <li>Filter: {@link Targets} decides which kinds count (hostile mobs by default; players only when asked for). Allies
 *       (the roster's {@code neverTarget}) never count.</li>
 *   <li>Range: candidates farther than the engage range are skipped.</li>
 *   <li>Line of sight: when required, a candidate without a clear line of sight is skipped, so the assistant never
 *       goes after something behind a wall. The relaxed legit level turns this off.</li>
 *   <li>Priority: {@link Priority#CLOSEST}, {@link Priority#LOWEST_HEALTH} or {@link Priority#THREAT} (the most
 *       dangerous first: whoever just hit you, a swelling creeper, aggressive mobs, ranged attackers, then distance).
 *       Ties go to the closer candidate.</li>
 * </ul>
 */
public final class TargetSelector {

    public enum Mode { REACTIVE, PROACTIVE }

    public enum Targets { HOSTILE, HOSTILE_AND_PLAYERS, PLAYERS }

    public enum Priority { THREAT, CLOSEST, LOWEST_HEALTH }

    /** Ally check by entity id (the roster's {@code neverTarget}). */
    @FunctionalInterface
    public interface AllyTest {
        boolean isAlly(int entityId);
    }

    // threat score weights: each tier outranks any distance difference inside the engage range
    static final double THREAT_RECENT_OPPONENT = 10_000;
    static final double THREAT_SWELLING = 5_000;
    static final double THREAT_AGGRESSIVE = 1_000;
    static final double THREAT_RANGED = 400;

    private TargetSelector() {
    }

    /**
     * @param recentOpponent the entity that last hurt the local player (or -1); it ranks first under THREAT
     * @return the chosen entity id, or -1 when nothing qualifies
     */
    public static int pick(TargetCandidates c, Targets targets, Priority priority, double range, boolean requireLos,
                           int recentOpponent, AllyTest allies) {
        double rangeSq = range * range;
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < c.size; i++) {
            if (c.distSq[i] > rangeSq || !accepts(targets, c.flags[i])) {
                continue;
            }
            if (requireLos && !c.has(i, TargetCandidates.LOS)) {
                continue;
            }
            int id = c.ids[i];
            if (allies != null && allies.isAlly(id)) {
                continue;
            }
            double score = score(c, i, priority, id == recentOpponent);
            if (score > bestScore || (score == bestScore && c.distSq[i] < bestDist)) {
                best = id;
                bestScore = score;
                bestDist = c.distSq[i];
            }
        }
        return best;
    }

    /**
     * The food objective: the closest food animal in range (and in sight when required). Only used when no hostile
     * qualifies, so food never comes before a fight.
     *
     * @return the entity id, or -1
     */
    public static int pickFood(TargetCandidates c, double range, boolean requireLos) {
        double rangeSq = range * range;
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < c.size; i++) {
            if (!c.has(i, TargetCandidates.FOOD) || c.distSq[i] > rangeSq
                    || (requireLos && !c.has(i, TargetCandidates.LOS))) {
                continue;
            }
            if (c.distSq[i] < bestDist) {
                best = c.ids[i];
                bestDist = c.distSq[i];
            }
        }
        return best;
    }

    static boolean accepts(Targets targets, int flags) {
        boolean hostile = (flags & TargetCandidates.HOSTILE) != 0;
        boolean player = (flags & TargetCandidates.PLAYER) != 0;
        return switch (targets) {
            case HOSTILE -> hostile && !player;
            case HOSTILE_AND_PLAYERS -> hostile || player;
            case PLAYERS -> player;
        };
    }

    private static double score(TargetCandidates c, int i, Priority priority, boolean recentOpponent) {
        double dist = Math.sqrt(c.distSq[i]);
        return switch (priority) {
            case CLOSEST -> -dist;
            case LOWEST_HEALTH -> -c.health[i] * 100.0 - dist;
            case THREAT -> {
                double s = -dist;
                if (recentOpponent) {
                    s += THREAT_RECENT_OPPONENT;
                }
                if (c.has(i, TargetCandidates.SWELLING)) {
                    s += THREAT_SWELLING;
                }
                if (c.has(i, TargetCandidates.AGGRESSIVE)) {
                    s += THREAT_AGGRESSIVE;
                }
                if (c.has(i, TargetCandidates.RANGED)) {
                    s += THREAT_RANGED;
                }
                yield s;
            }
        };
    }
}
