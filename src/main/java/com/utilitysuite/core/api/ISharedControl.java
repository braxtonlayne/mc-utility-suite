package com.utilitysuite.core.api;

/**
 * Entry point for control shared with the user (see {@link SharedGrant}) and for the facts about the user's own
 * input. Implemented by the core input layer; {@link #NONE} until it registers.
 */
public interface ISharedControl {

    /** Refuses every grant and reports no manual input. */
    ISharedControl NONE = new ISharedControl() {
        @Override
        public SharedGrant begin(String moduleId, ControlPriority priority, boolean wantsYaw) {
            return null;
        }

        @Override
        public ManualFacts manual() {
            return ManualFacts.NONE;
        }
    };

    /**
     * Null when refused: automation is stopped, a lease at or above {@code priority} holds MOVEMENT, another module
     * holds a shared grant, or a screen is open. While the grant is live, MOVEMENT counts as held by the module at
     * that priority for every other automation request. {@code moduleId} must be a constant string.
     */
    SharedGrant begin(String moduleId, ControlPriority priority, boolean wantsYaw);

    /** Never null. */
    ManualFacts manual();
}
