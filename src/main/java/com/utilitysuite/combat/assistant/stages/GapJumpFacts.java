package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.travel.movement.MovementPhysics;

/**
 * World facts for {@link GapJumpStage}: the voxels the parkour validator simulates on and whether SafeWalk guards
 * edges. Implemented over the client level in game ({@code McGapJumpFacts}) and by a course in tests. Client thread
 * only, allocation free.
 */
public interface GapJumpFacts {

    /** The voxels for this tick (a per-tick cache may be reset here), or null when no level is loaded. */
    MovementPhysics.Voxels voxels();

    /** SafeWalk is guarding edges (then a jump that costs any health is invalid). */
    boolean safeWalk();
}
