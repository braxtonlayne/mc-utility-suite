package com.utilitysuite.jobs.schematic.format;

/**
 * Upgrades a block state string saved by an older game version to the running version (block renames, property
 * changes). The format adapters stay pure: the game side installs a DataFixer-backed upgrader through
 * {@link SchematicFormats#setStateUpgrader}; without one (unit tests) states pass through unchanged.
 */
@FunctionalInterface
public interface StateUpgrader {

    /** No change. */
    StateUpgrader IDENTITY = (state, dataVersion) -> state;

    /**
     * @param state       canonical state string ({@code minecraft:oak_log[axis=y]})
     * @param dataVersion data version the file was saved with, or {@code -1} when unknown (no upgrade)
     */
    String upgrade(String state, int dataVersion);
}
