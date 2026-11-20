package com.utilitysuite.jobs.schematic.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Site survey of the target box, for the schematic job card.
 *
 * @param totalBlocks         cells that require a block
 * @param placed              of those, already in place (exactly or with different properties)
 * @param missing             cells to fill where the world is empty
 * @param wrongBlocks         block cells occupied by a different block (replace)
 * @param obstructions        air cells occupied by terrain or blocks that must be cleared
 * @param fluidConflicts      cells (block or air) holding water or lava
 * @param unsupportedGravity  sand/gravel/concrete powder/anvils that would fall when placed
 * @param outOfBounds         block cells above or below the world's build height
 * @param unloaded            cells in chunks that are not loaded (not yet surveyed)
 * @param minWorldY           lowest world Y of the box
 * @param maxWorldY           highest world Y of the box
 */
public record SiteSurveyReport(int totalBlocks, int placed, int missing, int wrongBlocks, int obstructions,
                               int fluidConflicts, int unsupportedGravity, int outOfBounds, int unloaded,
                               int minWorldY, int maxWorldY) {

    public boolean isClear() {
        return wrongBlocks == 0 && obstructions == 0 && fluidConflicts == 0 && unsupportedGravity == 0
                && outOfBounds == 0;
    }

    /** Human-readable lines for the job card. */
    public List<String> summaryLines() {
        List<String> out = new ArrayList<>();
        out.add("Placed " + placed + " / " + totalBlocks + " blocks, " + (totalBlocks - placed) + " to go");
        if (obstructions > 0) {
            out.add(obstructions + " obstruction(s) to clear inside the build area");
        }
        if (wrongBlocks > 0) {
            out.add(wrongBlocks + " wrong block(s) to replace");
        }
        if (fluidConflicts > 0) {
            out.add(fluidConflicts + " water/lava cell(s) in the build area");
        }
        if (unsupportedGravity > 0) {
            out.add(unsupportedGravity + " gravity block(s) without support (will fall)");
        }
        if (outOfBounds > 0) {
            out.add(outOfBounds + " block(s) outside the build height (Y " + minWorldY + ".." + maxWorldY + ")");
        }
        if (unloaded > 0) {
            out.add(unloaded + " cell(s) in unloaded chunks, not surveyed yet");
        }
        if (isClear() && unloaded == 0) {
            out.add("Site is clear");
        }
        return out;
    }
}
