package com.utilitysuite.world.overlays;

import static com.utilitysuite.world.overlays.BlockCategories.CUSTOM;
import static com.utilitysuite.world.overlays.BlockCategories.FLUIDS;
import static com.utilitysuite.world.overlays.BlockCategories.ORES;
import static com.utilitysuite.world.overlays.BlockCategories.ORE_PRECIOUS;
import static com.utilitysuite.world.overlays.BlockCategories.ORE_RARE;
import static com.utilitysuite.world.overlays.BlockCategories.SPAWNER;
import static com.utilitysuite.world.overlays.BlockCategories.STORAGE;

/** Block-visibility presets (instead of dozens of toggles). Custom ids are always included when set. */
public enum FilterPreset {
    RARE_ORES(ORE_RARE),
    VALUABLE_ORES(ORE_RARE | ORE_PRECIOUS),
    ALL_ORES(ORES),
    STORAGE_ONLY(STORAGE),
    SPAWNERS_ONLY(SPAWNER),
    FLUID_POCKETS(FLUIDS),
    CAVE_EXPLORER(ORE_RARE | ORE_PRECIOUS | SPAWNER | FLUIDS),
    EVERYTHING(ORES | STORAGE | SPAWNER | FLUIDS),
    CUSTOM_ONLY(0);

    private final int mask;

    FilterPreset(int mask) {
        this.mask = mask;
    }

    public int mask() {
        return mask | CUSTOM;
    }
}
