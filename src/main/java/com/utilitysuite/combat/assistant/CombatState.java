package com.utilitysuite.combat.assistant;

/** States of the reactive {@link CombatAssistant}. */
public enum CombatState {
    /** Armed or not, nothing happens until a confirmed trigger. */
    DORMANT,
    /** Locked onto a confirmed attacker; holds (or is waiting for) the COMBAT lease. */
    ENGAGED,
    /** Keys released, weapon slot restored; waits briefly for in-flight container clicks, then releases the lease. */
    DISENGAGING
}
