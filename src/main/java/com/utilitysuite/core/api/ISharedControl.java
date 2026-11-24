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

    /**
     * A grant that rides along with another module's MOVEMENT lease (a job walks, the module adds its jumps) instead
     * of taking MOVEMENT: the lease stays the holder and its keys stand. The grant covers {@link SharedGrant#axes},
     * {@link SharedGrant#jump} and {@link SharedGrant#sneak} only; it never starts or withholds a sprint and never
     * steers the yaw. Its writes are applied by the core like every other grant's (same tick, one-tick lapse).
     * <p>
     * Null when refused: automation is stopped, a screen is open, no lease of another module holds MOVEMENT, that
     * lease is at EMERGENCY or above, or another module holds the assist grant. The grant is bound to that one lease
     * and ends with it, whatever ends it (the user's own movement keys, a preemption, a release, a timeout), and on
     * Stop-All, a world unload and a screen opening. Calling again while the grant is live returns the same live
     * grant with its pending writes intact. {@code moduleId} must be a constant string. Allocation free. The default
     * refuses (an implementation without assist grants).
     */
    default SharedGrant beginAssist(String moduleId) {
        return null;
    }

    /** Never null. */
    ManualFacts manual();
}
