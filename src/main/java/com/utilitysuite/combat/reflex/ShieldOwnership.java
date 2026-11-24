package com.utilitysuite.combat.reflex;

import com.utilitysuite.combat.assistant.stages.ShieldThreatFeed;

/**
 * Who runs the user's shield. Pure and allocation free.
 * <p>
 * While the Combat Assistant is engaged and one of its stages owns the USE key (the shield duel's block-and-answer),
 * the shield reflex stands down, like the dodge and the fuse back-off: two sources with separate "raised" state on
 * one lease would lower each other's shield. While it is engaged the assistant also owns the camera, so the
 * "turn to block" option never turns during a fight.
 * <p>
 * Standing down is not going blind: for that time the reflex reports what it sees to the stage
 * ({@link #reportToStage}), and the stage raises the shield it already runs. Sensing and the reflex's settings stay
 * here, the key keeps one writer.
 */
final class ShieldOwnership {

    private ShieldOwnership() {
    }

    /**
     * @param armed        the shield reflex is on and allowed (module, setting, assistant or autopilot)
     * @param fighting     the assistant holds its fight lease
     * @param stageOwnsUse a stage of the assistant runs USE for this fight
     * @return the reflex may raise and hold the shield this tick; false also lowers a shield it still holds
     */
    static boolean reflexActs(boolean armed, boolean fighting, boolean stageOwnsUse) {
        return armed && !(fighting && stageOwnsUse);
    }

    /**
     * The reflex reports its threats to the stage instead of acting: exactly when it stands down for the stage, and
     * not while the user's own right click has the key (manual input wins).
     */
    static boolean reportsToStage(boolean armed, boolean fighting, boolean stageOwnsUse, boolean manualUse) {
        return armed && fighting && stageOwnsUse && !manualUse;
    }

    /**
     * One tick's report to the stage: the arrow and the blast as single impacts, the melee attacker as a standing
     * threat unless it is the duel target itself (the stage's block-and-answer reads that one's cooldown, and knows
     * about its axe). Ticks of {@code Integer.MAX_VALUE} mean "none" for a kind.
     *
     * @param viewYawDeg the player's yaw
     * @param targetId   the assistant's current target, -1 for none
     */
    static void reportToStage(ShieldThreatFeed feed, float viewYawDeg, double frontConeDeg,
                              int projTicks, float projDamage, float projBearing,
                              int blastTicks, float blastDamage, float blastBearing,
                              int meleeTicks, float meleeDamage, float meleeBearing, int meleeEntityId,
                              int targetId) {
        feed.begin(viewYawDeg, frontConeDeg);
        feed.offer(projTicks, projDamage, projBearing, false);
        feed.offer(blastTicks, blastDamage, blastBearing, false);
        if (meleeEntityId != targetId || targetId < 0) {
            feed.offer(meleeTicks, meleeDamage, meleeBearing, true);
        }
        feed.publish();
    }

    /**
     * The dodge stands down for an arrow the shield reflex will block: blocking and strafing at once is one answer
     * too many. True when the reflex is armed, has a usable shield, and the arrow comes from inside the front cone
     * (or the reflex may turn to it). An arrow from behind without turning is not covered, so the dodge keeps it.
     */
    static boolean shieldCoversArrow(boolean shieldArmed, boolean shieldUsable, boolean mayTurn, float relYawDeg,
                                     double frontConeDeg) {
        if (!shieldArmed || !shieldUsable || Float.isNaN(relYawDeg)) {
            return false;
        }
        return mayTurn || Math.abs(relYawDeg) <= frontConeDeg;
    }

    /** The reflex may turn the camera toward a threat: only with the option on and outside a fight. */
    static boolean reflexMayTurn(boolean turnOption, boolean fighting) {
        return turnOption && !fighting;
    }
}
