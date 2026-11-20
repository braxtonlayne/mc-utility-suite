package com.utilitysuite.jobs.schematic;

import java.util.List;
import java.util.Map;

/**
 * Display record for the UI's schematic job card. Built on request (not per tick).
 *
 * @param phase        LOADING, SCANNING, READY, BUILDING, PAUSED, COMPLETE or FAILED
 * @param required     remaining bill of materials (line key to count)
 * @param missing      what is still missing after inventory and storage (item to count)
 * @param unobtainable blocks that cannot be obtained in survival (block id to count)
 * @param flagged      notes needing manual attention (note to count)
 * @param survey       site survey summary lines
 */
public record SchematicJobCard(String name, String format, int sizeX, int sizeY, int sizeZ, int anchorX, int anchorY,
                               int anchorZ, String phase, float scanProgress, boolean gatherOnly, int totalBlocks,
                               int placedBlocks, Map<String, Integer> required, Map<String, Integer> missing,
                               Map<String, Integer> unobtainable, Map<String, Integer> flagged, List<String> survey,
                               int planSteps, int scaffolds, int unsupportedPlacements, int chestsNeeded,
                               List<String> notes) {

    public SchematicJobCard {
        required = Map.copyOf(required);
        missing = Map.copyOf(missing);
        unobtainable = Map.copyOf(unobtainable);
        flagged = Map.copyOf(flagged);
        survey = List.copyOf(survey);
        notes = List.copyOf(notes);
    }

    /** One status line for the HUD. */
    public String statusLine() {
        return switch (phase) {
            case "SCANNING" -> "Schematic " + name + ": scanning " + Math.round(scanProgress * 100) + "%";
            case "BUILDING", "READY" -> "Schematic " + name + ": " + placedBlocks + "/" + totalBlocks + " placed";
            default -> "Schematic " + name + ": " + phase.toLowerCase(java.util.Locale.ROOT);
        };
    }
}
