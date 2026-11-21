package com.utilitysuite.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.utilitysuite.core.api.ProfileConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The global "legit level": one choice that tunes how every automated system behaves, never which features are on.
 * <ul>
 *   <li><b>LEGIT</b>: slower camera, human spacing, line of sight required, visible rotations, the walk bridge
 *       disguised as a backward bridger.</li>
 *   <li><b>BALANCED</b>: the shipped defaults.</li>
 *   <li><b>RELAXED</b>: fast turns and placements, silent rotations, no line-of-sight requirement, instant walk
 *       bridging, no disguise.</li>
 * </ul>
 * Engine-free (only {@link ProfileConfig}); the menu applies a level and calls each module's
 * {@code onSettingChanged} for the knobs written. A knob whose key is missing from the profile counts as its BALANCED
 * value, because BALANCED is each module's default.
 */
public final class LegitPresets {

    public enum Level { LEGIT, BALANCED, RELAXED }

    /** One setting a level drives. {@code global}: stored in global.json ("core") instead of the world profile. */
    public record Knob(String moduleId, String key, Object legit, Object balanced, Object relaxed, boolean global) {

        public Object value(Level level) {
            return switch (level) {
                case LEGIT -> legit;
                case BALANCED -> balanced;
                case RELAXED -> relaxed;
            };
        }
    }

    public static final String SCHEMATIC = "schematic_toolkit";
    public static final String BRIDGE = "bridge_assist";
    public static final String COMBAT = "combat_assistant";

    public static final List<Knob> KNOBS = List.of(
            core("camera_speed_deg_per_sec", 160.0, 240.0, 450.0),
            knob(SCHEMATIC, "requireLineOfSight", true, true, false),
            knob(SCHEMATIC, "silentRotation", false, false, true),
            knob(SCHEMATIC, "placementDelayTicks", 6.0, 4.0, 1.0),
            knob(SCHEMATIC, "placementsPerWindow", 3, 4, 12),
            knob(BRIDGE, "rotation_mode", "VISIBLE", "VISIBLE", "SILENT"),
            knob(BRIDGE, "delay_mean_ticks", 5.0, 4.0, 1.0),
            knob(BRIDGE, "walk_pace", "LEGIT", "LEGIT", "INSTANT"),
            knob(BRIDGE, "server_look", "BRIDGER", "BRIDGER", "REAL"),
            knob(BRIDGE, "server_sneak", "AT_EDGE", "AT_EDGE", "OFF"),
            knob(BRIDGE, "hide_sprint", true, true, false),
            knob(COMBAT, "aim_speed", 0.75, 1.0, 2.0),
            knob(COMBAT, "los_timeout_ticks", 60, 100, 200),
            knob(COMBAT, "engage_requires_los", true, true, false),
            knob("movement_assist", "look_style", "NATURAL", "NATURAL", "FREE"),
            knob("combat_reflexes", "shield_turn", false, false, true),
            knob("combat_reflexes", "shield_front_cone", 70.0, 70.0, 90.0),
            knob("auto_torch", "relaxed", false, false, true),
            knob("lava_plug", "relaxed", false, false, true),
            knob("vein_work", "relaxed", false, false, true),
            knob("crop_work", "relaxed", false, false, true));

    private LegitPresets() {
    }

    private static Knob knob(String module, String key, Object legit, Object balanced, Object relaxed) {
        return new Knob(module, key, legit, balanced, relaxed, false);
    }

    private static Knob core(String key, Object legit, Object balanced, Object relaxed) {
        return new Knob("core", key, legit, balanced, relaxed, true);
    }

    /**
     * Writes every knob of the level.
     *
     * @return the knobs whose stored value changed (the caller notifies those modules)
     */
    public static List<Knob> apply(Level level, ProfileConfig profile, ProfileConfig global) {
        List<Knob> changed = new ArrayList<>();
        for (Knob k : KNOBS) {
            ProfileConfig target = k.global() ? global : profile;
            if (target == null) {
                continue;
            }
            Object v = k.value(level);
            if (matches(stored(target, k), v)) {
                continue;
            }
            if (v instanceof Boolean b) {
                target.set(k.moduleId(), k.key(), b);
            } else if (v instanceof Number n) {
                target.set(k.moduleId(), k.key(), n);
            } else {
                target.set(k.moduleId(), k.key(), String.valueOf(v));
            }
            changed.add(k);
        }
        return changed;
    }

    /** The level every knob currently matches, or null when they are mixed ("Custom"). */
    public static Level current(ProfileConfig profile, ProfileConfig global) {
        for (Level level : Level.values()) {
            boolean all = true;
            for (Knob k : KNOBS) {
                ProfileConfig target = k.global() ? global : profile;
                Object stored = target == null ? null : stored(target, k);
                if (!matches(stored == null ? k.balanced() : stored, k.value(level))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return level;
            }
        }
        return null;
    }

    /** The stored value as Boolean / Double / String, or null when the key is absent. */
    private static Object stored(ProfileConfig p, Knob k) {
        JsonObject o = p.root().has(k.moduleId()) && p.root().get(k.moduleId()).isJsonObject()
                ? p.root().getAsJsonObject(k.moduleId()) : null;
        if (o == null || !o.has(k.key())) {
            return null;
        }
        JsonElement e = o.get(k.key());
        if (!e.isJsonPrimitive()) {
            return null;
        }
        var prim = e.getAsJsonPrimitive();
        if (prim.isBoolean()) {
            return prim.getAsBoolean();
        }
        if (prim.isNumber()) {
            return prim.getAsDouble();
        }
        return prim.getAsString();
    }

    static boolean matches(Object stored, Object wanted) {
        if (stored == null) {
            return false;
        }
        if (stored instanceof Number a && wanted instanceof Number b) {
            return Math.abs(a.doubleValue() - b.doubleValue()) < 1e-6;
        }
        if (stored instanceof Boolean || wanted instanceof Boolean) {
            return stored.equals(wanted);
        }
        return String.valueOf(stored).toUpperCase(Locale.ROOT).equals(String.valueOf(wanted).toUpperCase(Locale.ROOT));
    }
}
