package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.schematic.bom.SubstitutionRules;
import com.utilitysuite.jobs.schematic.plan.BuildOrderPlanner;

import java.util.List;

/** Immutable snapshot of the toolkit's settings, re-read from the profile when it loads or a setting changes. */
public record SchematicSettings(boolean buildAssist, boolean silentRotation, int placementsPerWindow, int windowTicks,
                                double placementDelayTicks, int scanBudget, int rescanTicks, boolean ghostPreview,
                                int ghostDistance, boolean anyPlanksWhenHidden, boolean deepslateAsStone,
                                String scaffoldItem, String strictItems, int projectChests, int layersPerPhase,
                                int swapSlot, boolean autoNavigate) {

    public static final String GROUP_ASSIST = "Build Assist";
    public static final String GROUP_PREVIEW = "Preview";
    public static final String GROUP_MATERIALS = "Materials";

    public static final SchematicSettings DEFAULTS = new SchematicSettings(true, false, 4, 20, 4.0, 2048, 100, true,
            48, true, false, "minecraft:dirt", "", 2, 4, 8, true);

    public static List<SettingSpec> specs() {
        SchematicSettings d = DEFAULTS;
        return List.of(
                SettingSpec.bool("buildAssist", "Build Assist", d.buildAssist, GROUP_ASSIST,
                        "Place missing blocks within reach while you are near the site"),
                SettingSpec.bool("silentRotation", "Silent rotation", d.silentRotation, GROUP_ASSIST,
                        "Aim with packet-only rotation so your camera stays free while walking"),
                SettingSpec.integer("placementsPerWindow", "Placements per window", d.placementsPerWindow, 1, 20,
                        GROUP_ASSIST, "Maximum placements per time window"),
                SettingSpec.integer("windowTicks", "Window length (ticks)", d.windowTicks, 5, 200, GROUP_ASSIST,
                        "Length of the placement budget window").asAdvanced(),
                SettingSpec.decimal("placementDelayTicks", "Mean delay between placements", d.placementDelayTicks, 1,
                        40, GROUP_ASSIST, "Gaussian mean, in ticks").asAdvanced(),
                SettingSpec.integer("swapSlot", "Hotbar slot for swaps", d.swapSlot + 1, 1, 9, GROUP_ASSIST,
                        "Hotbar slot used when a block has to be pulled from the main inventory").asAdvanced(),
                SettingSpec.bool("autoNavigate", "Walk to the site", d.autoNavigate, GROUP_ASSIST,
                        "Ask the navigator to path to a distant build site"),
                SettingSpec.text("scaffoldItem", "Scaffold block", d.scaffoldItem, GROUP_ASSIST,
                        "Temporary support block, removed at the end"),
                SettingSpec.bool("ghostPreview", "Ghost preview", d.ghostPreview, GROUP_PREVIEW,
                        "Outline missing and wrong blocks at the anchor"),
                SettingSpec.integer("ghostDistance", "Preview distance", d.ghostDistance, 8, 128, GROUP_PREVIEW,
                        "Only outline blocks within this distance"),
                SettingSpec.bool("anyPlanksWhenHidden", "Any planks for hidden blocks", d.anyPlanksWhenHidden,
                        GROUP_MATERIALS, "Hidden plank blocks may use any plank variant"),
                SettingSpec.bool("deepslateAsStone", "Deepslate as stone", d.deepslateAsStone, GROUP_MATERIALS,
                        "Deepslate building blocks may be replaced by their stone counterparts"),
                SettingSpec.text("strictItems", "Strict items", d.strictItems, GROUP_MATERIALS,
                        "Comma-separated item ids that are never substituted"),
                SettingSpec.integer("projectChests", "Project chests", d.projectChests, 0, 32, GROUP_MATERIALS,
                        "Chests available for material staging"),
                SettingSpec.integer("layersPerPhase", "Layers per staging phase", d.layersPerPhase, 1, 64,
                        GROUP_MATERIALS, "Layers grouped into one staging phase").asAdvanced(),
                SettingSpec.integer("scanBudget", "World scan budget", d.scanBudget, 64, 16384, GROUP_MATERIALS,
                        "Blocks compared with the world per tick").asAdvanced(),
                SettingSpec.integer("rescanTicks", "Rescan interval", d.rescanTicks, 20, 1200, GROUP_MATERIALS,
                        "Ticks between diff refreshes").asAdvanced());
    }

    public static SchematicSettings read(ProfileConfig p, String id) {
        SchematicSettings d = DEFAULTS;
        return new SchematicSettings(
                p.getBool(id, "buildAssist", d.buildAssist),
                p.getBool(id, "silentRotation", d.silentRotation),
                clamp(p.getInt(id, "placementsPerWindow", d.placementsPerWindow), 1, 20),
                clamp(p.getInt(id, "windowTicks", d.windowTicks), 5, 200),
                Math.max(1, Math.min(40, p.getDouble(id, "placementDelayTicks", d.placementDelayTicks))),
                clamp(p.getInt(id, "scanBudget", d.scanBudget), 64, 16384),
                clamp(p.getInt(id, "rescanTicks", d.rescanTicks), 20, 1200),
                p.getBool(id, "ghostPreview", d.ghostPreview),
                clamp(p.getInt(id, "ghostDistance", d.ghostDistance), 8, 128),
                p.getBool(id, "anyPlanksWhenHidden", d.anyPlanksWhenHidden),
                p.getBool(id, "deepslateAsStone", d.deepslateAsStone),
                p.getString(id, "scaffoldItem", d.scaffoldItem),
                p.getString(id, "strictItems", d.strictItems),
                clamp(p.getInt(id, "projectChests", d.projectChests), 0, 32),
                clamp(p.getInt(id, "layersPerPhase", d.layersPerPhase), 1, 64),
                clamp(p.getInt(id, "swapSlot", d.swapSlot + 1), 1, 9) - 1,
                p.getBool(id, "autoNavigate", d.autoNavigate));
    }

    /** The substitution rules these settings describe. */
    public SubstitutionRules rules() {
        SubstitutionRules.Builder b = SubstitutionRules.builder();
        if (anyPlanksWhenHidden) {
            b.anyPlanks(SubstitutionRules.Scope.HIDDEN_ONLY);
        }
        if (deepslateAsStone) {
            b.deepslateToStone(SubstitutionRules.Scope.ANYWHERE);
        }
        for (String s : strictItems.split(",")) {
            if (!s.isBlank()) {
                b.strict(s.trim());
            }
        }
        return b.build();
    }

    public BuildOrderPlanner.Options planOptions() {
        String item = scaffoldItem == null || scaffoldItem.isBlank() ? "minecraft:dirt" : scaffoldItem.trim();
        return new BuildOrderPlanner.Options(
                com.utilitysuite.jobs.schematic.model.BlockStateString.normalizeId(item), 12);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
