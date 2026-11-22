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
    /** BALANCED values of the movement assist's key-change knobs (also the module's defaults). */
    public static final int KEY_CHANGES_BALANCED = 6;
    public static final int KEY_HOLD_BALANCED = 2;
    public static final int REACTION_BALANCED = 0;
    /**
     * Yaw co-steer of a committed jump (design 2026-11-22, 3.1; owner decision D1: from BALANCED up): degrees per second
     * and degrees per jump, for Co-pilot (GUIDE) and Takeover (CARRY). LEGIT is 0 everywhere: the camera never moves by
     * itself there. The core caps every rate at 450 deg/s whatever is stored.
     */
    public static final String CO_STEER_RATE = "co_steer_deg_per_sec";
    public static final String CO_STEER_MAX = "co_steer_max_deg";
    public static final String CO_STEER_CARRY_RATE = "co_steer_carry_deg_per_sec";
    public static final String CO_STEER_CARRY_MAX = "co_steer_carry_max_deg";
    public static final double CO_STEER_RATE_BALANCED = 150.0;
    public static final double CO_STEER_MAX_BALANCED = 25.0;
    public static final double CO_STEER_CARRY_RATE_BALANCED = 240.0;
    public static final double CO_STEER_CARRY_MAX_BALANCED = 90.0;

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
            // turn gain scales the user's own mouse toward the ideal takeoff yaw: Legit stays keys only
            knob("movement_assist", "turn_gain", false, true, true),
            // parkour commitment key changes per jump (design 2026-11-22, 3.1): at most N changes (0 = unlimited),
            // each held at least M ticks, the first after a reaction delay (LEGIT: 2 to 4 ticks around 3)
            knob("movement_assist", "assist_key_changes", 3, KEY_CHANGES_BALANCED, 0),
            knob("movement_assist", "assist_key_hold_ticks", 3, KEY_HOLD_BALANCED, 1),
            knob("movement_assist", "assist_reaction_ticks", 3, REACTION_BALANCED, 0),
            // yaw co-steer during a committed jump (D1): never on LEGIT, bounded on BALANCED, the hard cap on RELAXED
            knob("movement_assist", CO_STEER_RATE, 0.0, CO_STEER_RATE_BALANCED, 450.0),
            knob("movement_assist", CO_STEER_MAX, 0.0, CO_STEER_MAX_BALANCED, 180.0),
            knob("movement_assist", CO_STEER_CARRY_RATE, 0.0, CO_STEER_CARRY_RATE_BALANCED, 450.0),
            knob("movement_assist", CO_STEER_CARRY_MAX, 0.0, CO_STEER_CARRY_MAX_BALANCED, 180.0),
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
        return currentIgnoring(profile, global, null, null);
    }

    /**
     * Like {@link #current} but ignoring one knob (null module/key: ignore none). Lets a knob added after a profile
     * was set to a level find out which level the rest of the profile is on.
     */
    public static Level currentIgnoring(ProfileConfig profile, ProfileConfig global, String moduleId, String key) {
        for (Level level : Level.values()) {
            boolean all = true;
            for (Knob k : KNOBS) {
                if (moduleId != null && k.moduleId().equals(moduleId) && k.key().equals(key)) {
                    continue;
                }
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

    /**
     * A boolean knob's effective value: the stored value when the key is present; when it is absent, the knob's value
     * for the level the rest of the profile is on (a profile left on LEGIT before the knob existed reads LEGIT's
     * value, not BALANCED's), or the BALANCED value for a Custom profile.
     */
    public static boolean boolKnob(ProfileConfig profile, ProfileConfig global, String moduleId, String key) {
        Knob knob = null;
        for (Knob k : KNOBS) {
            if (k.moduleId().equals(moduleId) && k.key().equals(key)) {
                knob = k;
                break;
            }
        }
        if (knob == null) {
            throw new IllegalArgumentException("no knob " + moduleId + "." + key);
        }
        ProfileConfig target = knob.global() ? global : profile;
        Object stored = target == null ? null : stored(target, knob);
        if (stored instanceof Boolean b) {
            return b;
        }
        Level level = currentIgnoring(profile, global, moduleId, key);
        Object v = knob.value(level == null ? Level.BALANCED : level);
        return v instanceof Boolean b && b;
    }

    /**
     * A numeric knob's effective value: the stored number when the key is present; when it is absent, the knob's value
     * for {@link #storedLevel} (so a profile left on LEGIT before the knob existed reads LEGIT's value), or the
     * BALANCED value when the stored knobs are mixed.
     */
    public static double numKnob(ProfileConfig profile, ProfileConfig global, String moduleId, String key) {
        Knob knob = find(moduleId, key);
        ProfileConfig target = knob.global() ? global : profile;
        Object stored = target == null ? null : stored(target, knob);
        if (stored instanceof Number n) {
            return n.doubleValue();
        }
        Level level = storedLevel(profile, global);
        Object v = knob.value(level == null ? Level.BALANCED : level);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    /**
     * The level every STORED knob matches, absent keys ignored (unlike {@link #current}, which reads an absent key as
     * BALANCED): a profile set to a level before newer knobs existed still reads that level. BALANCED is tried first
     * (the default, and the answer when the stored knobs fit more than one level), then LEGIT, then RELAXED; null when
     * the stored knobs are mixed. A profile with nothing stored reads BALANCED.
     */
    public static Level storedLevel(ProfileConfig profile, ProfileConfig global) {
        Level[] order = {Level.BALANCED, Level.LEGIT, Level.RELAXED};
        for (Level level : order) {
            boolean all = true;
            for (Knob k : KNOBS) {
                ProfileConfig target = k.global() ? global : profile;
                Object stored = target == null ? null : stored(target, k);
                if (stored != null && !matches(stored, k.value(level))) {
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

    private static Knob find(String moduleId, String key) {
        for (Knob k : KNOBS) {
            if (k.moduleId().equals(moduleId) && k.key().equals(key)) {
                return k;
            }
        }
        throw new IllegalArgumentException("no knob " + moduleId + "." + key);
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
