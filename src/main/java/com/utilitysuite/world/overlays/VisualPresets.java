package com.utilitysuite.world.overlays;

import java.util.List;

/**
 * One-click visual presets for the World tab: module enable flags plus setting writes, applied through the generic
 * settings API (every write goes through {@code onSettingChanged} and is saved with the profile). Pure data; module
 * ids and keys are checked against the modules' {@code settings()} specs by {@code VisualPresetsTest}.
 */
public final class VisualPresets {

    static final String OVERLAYS = "world_overlays";
    static final String CAMERA = "scout_camera";
    static final String TERRAIN = "terrain_assist";
    static final String ORESIM = "seed_oresim";
    static final String RADAR = "world_radar";
    static final String SCHEMATIC = "schematic_toolkit";

    /** Turn a module on or off. */
    public record Toggle(String moduleId, boolean enabled) {
    }

    /** Write one setting: Boolean, Number, or String (enum constant name for choices). */
    public record Setting(String moduleId, String key, Object value) {
    }

    public record Preset(String name, String tooltip, List<Toggle> toggles, List<Setting> settings) {
    }

    public static final Preset CAVE_RUN = new Preset("Cave Run",
            "Exposed ores, fluid pockets, terrain hazards and fullbright",
            List.of(new Toggle(OVERLAYS, true), new Toggle(TERRAIN, true), new Toggle(CAMERA, true)),
            List.of(new Setting(OVERLAYS, "preset", "ALL_ORES"),
                    new Setting(OVERLAYS, "exposed_only", true),
                    new Setting(OVERLAYS, "fluids", true),
                    new Setting(TERRAIN, "hazards", true),
                    new Setting(CAMERA, "fullbright", true)));

    public static final Preset DEBRIS_RUN = new Preset("Ancient Debris Run",
            "Outlines ancient debris only (replaces the custom id list) and warns about lava pockets",
            List.of(new Toggle(OVERLAYS, true)),
            List.of(new Setting(OVERLAYS, "preset", "CUSTOM_ONLY"),
                    new Setting(OVERLAYS, "custom_ids", "minecraft:ancient_debris"),
                    new Setting(OVERLAYS, "exposed_only", false),
                    new Setting(OVERLAYS, "fluids", true)));

    public static final Preset BUILDER_VISION = new Preset("Builder Vision",
            "Schematic ghost preview, fullbright and a decluttered, fog-free view",
            List.of(new Toggle(SCHEMATIC, true), new Toggle(CAMERA, true)),
            List.of(new Setting(SCHEMATIC, "ghostPreview", true),
                    new Setting(CAMERA, "fullbright", true),
                    new Setting(CAMERA, "no_fire", true),
                    new Setting(CAMERA, "no_pumpkin", true),
                    new Setting(CAMERA, "no_portal", true),
                    new Setting(CAMERA, "no_effect_fog", true)));

    public static final Preset CLEAN = new Preset("Clean",
            "Turns off overlays, radar, OreSim, fullbright, cave/fluid layers and the hazard overlay",
            List.of(new Toggle(OVERLAYS, false), new Toggle(RADAR, false), new Toggle(ORESIM, false)),
            List.of(new Setting(OVERLAYS, "caves", false),
                    new Setting(OVERLAYS, "fluids", false),
                    new Setting(CAMERA, "fullbright", false),
                    new Setting(TERRAIN, "hazards", false)));

    public static final List<Preset> ALL = List.of(CAVE_RUN, DEBRIS_RUN, BUILDER_VISION, CLEAN);

    private VisualPresets() {
    }
}
