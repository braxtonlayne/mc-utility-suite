package com.utilitysuite.combat.reflex;

/**
 * Who runs the user's shield. Pure and allocation free.
 * <p>
 * While the Combat Assistant is engaged and one of its stages owns the USE key (the shield duel's block-and-answer),
 * the shield reflex stands down, like the dodge and the fuse back-off: two sources with separate "raised" state on
 * one lease would lower each other's shield. While it is engaged the assistant also owns the camera, so the
 * "turn to block" option never turns during a fight.
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

    /** The reflex may turn the camera toward a threat: only with the option on and outside a fight. */
    static boolean reflexMayTurn(boolean turnOption, boolean fighting) {
        return turnOption && !fighting;
    }
}
