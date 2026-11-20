package com.utilitysuite.jobs.planner;

/** Why a step is in the plan; drives milestone grouping in {@link Plan#milestones()}. */
public enum StepRole {
    /** Ordinary prerequisite with its own milestone. */
    PRIMARY,
    /** Intermediate part (sticks): folded silently into the next step's milestone. */
    COMPONENT,
    /** Secondary station (furnace): shown as "Next + Furnace" when the next step is a craft. */
    STATION,
    /** Fuel acquisition: shown as "Previous + Fuel" when it follows a mining step. */
    FUEL,
    /** A pickaxe needed for a harvest tier. */
    TOOL,
    /** The goal itself. */
    TARGET,
    /** The closing return/deposit errand. */
    RETURN
}
