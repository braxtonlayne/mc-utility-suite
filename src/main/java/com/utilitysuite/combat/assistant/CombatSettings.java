package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;

/**
 * Settings of the {@link CombatAssistant}, read from the active profile under the module id. Plain mutable fields so
 * the per-tick code reads them without lookups. Everything past the two ranges sits in the collapsed Advanced drawer.
 */
public final class CombatSettings {

    public enum SprintReset { W_RELEASE, S_TAP, OFF }

    public static final String DISENGAGE_RADIUS = "disengage_radius";
    public static final String ALLY_DEFENSE_RANGE = "ally_defense_range";
    public static final String LOS_TIMEOUT_TICKS = "los_timeout_ticks";
    public static final String AIM_SPEED = "aim_speed";
    public static final String VELOCITY_LEAD = "velocity_lead";
    public static final String LEAD_TICKS = "lead_ticks";
    public static final String CRIT_SYNC = "crit_sync";
    public static final String SPACING_MIN = "spacing_min";
    public static final String SPACING_MAX = "spacing_max";
    public static final String STRAFE = "strafe";
    public static final String SPRINT_RESET = "sprint_reset";
    public static final String SHIELD_BREAK = "shield_break";
    public static final String MACE_SWAP = "mace_swap";
    public static final String WIND_CHARGE = "wind_charge_launch";
    public static final String SUSTAIN = "sustain";
    public static final String LOW_HEALTH = "low_health";
    public static final String TOTEMS = "totems";
    public static final String GAPPLES = "golden_apples";
    public static final String WHOLE_INVENTORY = "whole_inventory_consumables";
    public static final String SWAP_BACK = "swap_back_pulled";
    public static final String KEEP_TOTEM = "keep_offhand_totem";
    public static final String JUMP_RESET = "jump_reset";

    public double disengageRadius = 16.0;
    public double allyDefenseRange = 12.0;
    public int losTimeoutTicks = 100;
    public float aimSpeed = 1.0f;
    public boolean velocityLead = true;
    public double leadTicks = 2.0;
    public boolean critSync = true;
    public double spacingMin = 2.7;
    public double spacingMax = 3.1;
    public boolean strafe = true;
    public SprintReset sprintReset = SprintReset.W_RELEASE;
    public boolean shieldBreak = true;
    public boolean maceSwap = false;
    public boolean windCharge = false;
    public boolean sustain = true;
    public float lowHealth = 8f;
    public boolean totems = true;
    public boolean gapples = true;
    public boolean wholeInventory = true;
    public boolean swapBack = true;
    public boolean keepTotem = true;
    public boolean jumpReset = false;

    public void read(ProfileConfig p, String id) {
        wholeInventory = p.getBool(id, WHOLE_INVENTORY, true);
        swapBack = p.getBool(id, SWAP_BACK, true);
        keepTotem = p.getBool(id, KEEP_TOTEM, true);
        jumpReset = p.getBool(id, JUMP_RESET, false);
        disengageRadius = p.getDouble(id, DISENGAGE_RADIUS, 16.0);
        allyDefenseRange = p.getDouble(id, ALLY_DEFENSE_RANGE, 12.0);
        losTimeoutTicks = p.getInt(id, LOS_TIMEOUT_TICKS, 100);
        aimSpeed = (float) p.getDouble(id, AIM_SPEED, 1.0);
        velocityLead = p.getBool(id, VELOCITY_LEAD, true);
        leadTicks = p.getDouble(id, LEAD_TICKS, 2.0);
        critSync = p.getBool(id, CRIT_SYNC, true);
        spacingMin = p.getDouble(id, SPACING_MIN, 2.7);
        spacingMax = Math.max(spacingMin + 0.1, p.getDouble(id, SPACING_MAX, 3.1));
        strafe = p.getBool(id, STRAFE, true);
        sprintReset = parse(p.getString(id, SPRINT_RESET, SprintReset.W_RELEASE.name()));
        shieldBreak = p.getBool(id, SHIELD_BREAK, true);
        maceSwap = p.getBool(id, MACE_SWAP, false);
        windCharge = p.getBool(id, WIND_CHARGE, false);
        sustain = p.getBool(id, SUSTAIN, true);
        lowHealth = (float) p.getDouble(id, LOW_HEALTH, 8.0);
        totems = p.getBool(id, TOTEMS, true);
        gapples = p.getBool(id, GAPPLES, true);
    }

    private static SprintReset parse(String s) {
        for (SprintReset r : SprintReset.values()) {
            if (r.name().equalsIgnoreCase(s)) {
                return r;
            }
        }
        return SprintReset.W_RELEASE;
    }

    public static List<SettingSpec> specs() {
        return List.of(
                SettingSpec.decimal(DISENGAGE_RADIUS, "Disengage radius", 16.0, 6.0, 48.0, "",
                        "Stop when the target gets farther than this many blocks."),
                SettingSpec.decimal(ALLY_DEFENSE_RANGE, "Ally defense range", 12.0, 2.0, 32.0, "",
                        "Defend roster players marked 'defend' only within this many blocks."),
                SettingSpec.integer(LOS_TIMEOUT_TICKS, "Line-of-sight timeout (ticks)", 100, 20, 400, "Aim",
                        "Disengage after the target has been out of sight this long.").asAdvanced(),
                SettingSpec.decimal(AIM_SPEED, "Aim speed", 1.0, 0.25, 3.0, "Aim",
                        "Multiplier on the smoother's camera speed.").asAdvanced(),
                SettingSpec.bool(VELOCITY_LEAD, "Lead moving targets", true, "Aim",
                        "Aim slightly ahead of a moving target.").asAdvanced(),
                SettingSpec.decimal(LEAD_TICKS, "Lead (ticks)", 2.0, 0.0, 6.0, "Aim",
                        "How far ahead to lead, in ticks of target movement.").asAdvanced(),
                SettingSpec.bool(CRIT_SYNC, "Critical-hit timing", true, "Cooldown",
                        "Hop and swing while falling for critical hits.").asAdvanced(),
                SettingSpec.decimal(SPACING_MIN, "Spacing min (blocks)", 2.7, 1.5, 4.0, "Spacing",
                        "Back off when closer than this.").asAdvanced(),
                SettingSpec.decimal(SPACING_MAX, "Spacing max (blocks)", 3.1, 1.8, 4.5, "Spacing",
                        "Close in when farther than this.").asAdvanced(),
                SettingSpec.bool(STRAFE, "Strafe", true, "Spacing",
                        "Move sideways around the target, away from cliffs and lava.").asAdvanced(),
                SettingSpec.choice(SPRINT_RESET, "Sprint reset", SprintReset.W_RELEASE, "Spacing",
                        "Brief forward release or back tap after a hit.").asAdvanced(),
                SettingSpec.bool(SHIELD_BREAK, "Axe vs. shields", true, "Shield & weapons",
                        "Swap to a hotbar axe to disable a raised shield, then back.").asAdvanced(),
                SettingSpec.bool(MACE_SWAP, "Mace when falling", false, "Shield & weapons",
                        "Swap to a hotbar mace for swings made while falling.").asAdvanced(),
                SettingSpec.bool(WIND_CHARGE, "Wind charge launch", false, "Shield & weapons",
                        "Launch with a wind charge before a mace swing.").asAdvanced(),
                SettingSpec.bool(SUSTAIN, "Emergency sustain", true, "Emergency",
                        "Back off, equip a totem or eat a golden apple at low health.").asAdvanced(),
                SettingSpec.decimal(LOW_HEALTH, "Low health (half-hearts)", 8.0, 2.0, 16.0, "Emergency",
                        "Health at which to back off or eat.").asAdvanced(),
                SettingSpec.bool(TOTEMS, "Totem to offhand", true, "Emergency",
                        "Move a totem to the offhand when lethal damage is imminent.").asAdvanced(),
                SettingSpec.bool(GAPPLES, "Golden apples", true, "Emergency",
                        "Eat a hotbar golden apple at low health.").asAdvanced(),
                SettingSpec.bool(WHOLE_INVENTORY, "Use items from whole inventory", true, "Emergency",
                        "Pull golden apples or healing/regeneration potions from the main inventory into the hotbar"
                                + " (single-player or allow-listed servers only).").asAdvanced(),
                SettingSpec.bool(SWAP_BACK, "Swap pulled items back", true, "Emergency",
                        "Return a pulled item to its inventory slot after use.").asAdvanced(),
                SettingSpec.bool(KEEP_TOTEM, "Keep a totem in offhand", true, "Emergency",
                        "While armed, move a totem to an empty offhand right away (single-player or allow-listed"
                                + " servers only).").asAdvanced(),
                SettingSpec.bool(JUMP_RESET, "Jump reset on knockback", false, "Spacing",
                        "Tap jump when hit while engaged and on the ground (single-player or allow-listed servers"
                                + " only).").asAdvanced());
    }
}
