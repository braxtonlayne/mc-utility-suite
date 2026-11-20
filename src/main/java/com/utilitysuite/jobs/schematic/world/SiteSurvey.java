package com.utilitysuite.jobs.schematic.world;

import com.utilitysuite.jobs.schematic.model.BlockCategory;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;

/**
 * Derives the {@link SiteSurveyReport} from a status snapshot of a completed {@link PlacementScan}: obstructions to
 * clear, wrong blocks, water/lava conflicts, unsupported gravity blocks and cells outside the build height.
 */
public final class SiteSurvey {

    private SiteSurvey() {
    }

    /**
     * @param status      status snapshot (same indexing as the volume)
     * @param scan        the scan that produced it (for origin and the bottom-layer support bits)
     * @param minBuildY   lowest buildable world Y (inclusive), e.g. -64
     * @param maxBuildY   highest buildable world Y (exclusive), e.g. 320
     */
    public static SiteSurveyReport survey(PlacementScan scan, byte[] status, int minBuildY, int maxBuildY) {
        return survey(scan, status, scan.unsupportedBelowSnapshot(), minBuildY, maxBuildY);
    }

    /** Same, with a snapshot of the scan's bottom-layer support bits (safe off the client thread). */
    public static SiteSurveyReport survey(PlacementScan scan, byte[] status, java.util.BitSet unsupportedBelow,
                                          int minBuildY, int maxBuildY) {
        SchematicVolume v = scan.volume();
        int p = v.paletteSize();
        boolean[] gravity = new boolean[p];
        boolean[] noSupport = new boolean[p];
        for (int i = 0; i < p; i++) {
            if (v.isBlock(i)) {
                BlockCategory c = BlockCategory.of(v.paletteEntry(i));
                gravity[i] = c == BlockCategory.GRAVITY;
                noSupport[i] = c == BlockCategory.ATTACHMENT;
            } else {
                noSupport[i] = v.isAir(i);
            }
        }
        int total = 0, placed = 0, missing = 0, wrong = 0, obstructions = 0, fluid = 0, unsupported = 0, oob = 0;
        int unloaded = 0;
        int n = v.volume();
        int layer = v.sizeX() * v.sizeZ();
        for (int i = 0; i < n; i++) {
            int pi = v.paletteIndex(i);
            byte s = status[i];
            if (SchematicVolume.isVoid(pi)) {
                continue;
            }
            if (s == PlacementStatus.UNLOADED || s == PlacementStatus.UNKNOWN) {
                unloaded++;
            }
            if (v.isAir(pi)) {
                if (s == PlacementStatus.WRONG) {
                    obstructions++;
                } else if (s == PlacementStatus.FLUID) {
                    fluid++;
                }
                continue;
            }
            total++;
            int wy = scan.originY() + i / layer;
            if (wy < minBuildY || wy >= maxBuildY) {
                oob++;
            }
            switch (s) {
                case PlacementStatus.MATCH, PlacementStatus.SAME_BLOCK -> placed++;
                case PlacementStatus.MISSING -> missing++;
                case PlacementStatus.WRONG -> wrong++;
                case PlacementStatus.FLUID -> {
                    fluid++;
                    missing++;
                }
                default -> {
                }
            }
            if (gravity[pi] && !PlacementStatus.isPlaced(s)) {
                if (i < layer) {
                    if (unsupportedBelow.get(i)) {
                        unsupported++;
                    }
                } else if (noSupport[v.paletteIndex(i - layer)]) {
                    unsupported++;
                }
            }
        }
        return new SiteSurveyReport(total, placed, missing, wrong, obstructions, fluid, unsupported, oob, unloaded,
                scan.originY(), scan.originY() + v.sizeY() - 1);
    }
}
