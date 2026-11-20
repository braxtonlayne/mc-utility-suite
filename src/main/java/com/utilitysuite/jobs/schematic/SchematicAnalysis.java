package com.utilitysuite.jobs.schematic;

import com.utilitysuite.jobs.schematic.bom.BillOfMaterials;
import com.utilitysuite.jobs.schematic.bom.MaterialDiff;
import com.utilitysuite.jobs.schematic.bom.SubstitutionRules;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.plan.BuildOrderPlanner;
import com.utilitysuite.jobs.schematic.plan.BuildPlan;
import com.utilitysuite.jobs.schematic.plan.StagingPlanner;
import com.utilitysuite.jobs.schematic.world.PlacementScan;
import com.utilitysuite.jobs.schematic.world.SiteSurvey;
import com.utilitysuite.jobs.schematic.world.SiteSurveyReport;

import java.util.BitSet;
import java.util.Map;

/**
 * Immutable result of one analysis pass, computed on the schematic thread from snapshots taken on the client thread:
 * remaining bill of materials, supply diff, site survey, build order and staging plan.
 */
public record SchematicAnalysis(BillOfMaterials bom, MaterialDiff diff, SiteSurveyReport survey, BuildPlan plan,
                                StagingPlanner.StagingPlan staging, int[] ghostCells) {

    /** Inputs captured on the client thread. */
    public record Inputs(SchematicVolume volume, PlacementScan scan, byte[] status, BitSet unsupportedBelow,
                         Map<String, Integer> inventory, Map<String, Integer> storage, SubstitutionRules rules,
                         BuildOrderPlanner.Options planOptions, int minBuildY, int maxBuildY, int projectChests,
                         int layersPerPhase, int ghostLimit) {
    }

    public static SchematicAnalysis compute(Inputs in) {
        BillOfMaterials bom = BillOfMaterials.compute(in.volume(), in.status(), in.rules());
        MaterialDiff diff = MaterialDiff.compute(bom, in.inventory(), in.storage());
        SiteSurveyReport survey = SiteSurvey.survey(in.scan(), in.status(), in.unsupportedBelow(), in.minBuildY(),
                in.maxBuildY());
        BuildPlan plan = BuildOrderPlanner.plan(in.volume(), in.status(), in.planOptions());
        StagingPlanner.StagingPlan staging = StagingPlanner.plan(plan, in.layersPerPhase(),
                Math.max(0, in.projectChests()), 27);
        int[] ghosts = ghostCells(in.volume(), in.status(), in.ghostLimit());
        return new SchematicAnalysis(bom, diff, survey, plan, staging, ghosts);
    }

    /** Cells that still need a block (missing, wrong, flooded), up to {@code limit}, in bottom-up storage order. */
    static int[] ghostCells(SchematicVolume v, byte[] status, int limit) {
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        int n = v.volume();
        for (int i = 0; i < n && out.size() < limit; i++) {
            if (v.isBlock(v.paletteIndex(i))
                    && com.utilitysuite.jobs.schematic.world.PlacementStatus.needsPlacing(status[i])) {
                out.add(i);
            }
        }
        return out.toIntArray();
    }
}
