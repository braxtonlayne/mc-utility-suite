package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.tactics.CombatVoxels;

/**
 * {@link CombatVoxels} over the live client level, for the arena read and the pearl landing simulation.
 * Client thread only; must not allocate per lookup (one reused {@code BlockPos.MutableBlockPos}).
 * <p>
 * CONTRACT STUB: the constructor and the interface are fixed for the volley; the body is filled in by the arena-field
 * task. Until then every block reads as {@link CombatVoxels#UNKNOWN}.
 */
public final class ArenaVoxels implements CombatVoxels {

    public ArenaVoxels() {
    }

    @Override
    public int kind(int x, int y, int z) {
        return UNKNOWN;
    }
}
