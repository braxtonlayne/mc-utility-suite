package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;

/**
 * The autopilot module ({@link IAutopilot}). It owns no input itself: the reflex modules ask {@link #allows} and run
 * their reflex through the core when it says yes, even while their own module is off. Off by default; its own toggle
 * sits pinned across the top of the menu and on an (unbound) key. Engine free.
 */
public final class Autopilot implements IModule, IConfigurable, IAutopilot {

    public static final String ID = "autopilot";
    static final String K_CLUTCH = "reflex_fall_clutch";
    static final String K_PARKOUR = "reflex_parkour";
    static final String K_BLOCKS = "reflex_parkour_blocks";
    static final String K_TOTEM = "reflex_totem";
    static final String K_REACH = "reach";

    private boolean enabled;
    private boolean clutch = true;
    private boolean parkour = true;
    private boolean blocks = true;
    private boolean totem = true;
    private Reach reach = Reach.ASSIST;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.CORE;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void onTick() {
        // nothing per tick: the reflexes ask allows() where they run
    }

    @Override
    public boolean isOn() {
        return enabled;
    }

    @Override
    public boolean allows(Reflex reflex) {
        if (!enabled) {
            return false;
        }
        return switch (reflex) {
            case FALL_CLUTCH -> clutch;
            case PARKOUR -> parkour && reach == Reach.ASSIST;
            case PARKOUR_BLOCKS -> blocks && reach == Reach.ASSIST;
            case TOTEM -> totem;
            // Contract stubs: allowed while the autopilot is on, until each gets its own setting here.
            case SHIELD, DODGE, THREAT_ESCAPE, ELYTRA_SWAP, OFFHAND -> true;
        };
    }

    @Override
    public Reach reach() {
        return reach;
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        clutch = p.getBool(ID, K_CLUTCH, true);
        parkour = p.getBool(ID, K_PARKOUR, true);
        blocks = p.getBool(ID, K_BLOCKS, true);
        totem = p.getBool(ID, K_TOTEM, true);
        try {
            reach = Reach.valueOf(p.getString(ID, K_REACH, Reach.ASSIST.name()));
        } catch (IllegalArgumentException e) {
            reach = Reach.ASSIST;
        }
    }

    @Override
    public void onSettingChanged(String key) {
        com.utilitysuite.core.api.IModState s = com.utilitysuite.core.api.Services.modState();
        onProfileLoaded(s.profile());
    }

    @Override
    public String displayName() {
        return "Autopilot";
    }

    /** One line for the top bar: what the autopilot may do right now. */
    public String summary() {
        if (!enabled) {
            return "Off. Turn it on to let reflexes act for you.";
        }
        StringBuilder sb = new StringBuilder();
        if (clutch) {
            sb.append("fall clutch");
        }
        if (totem) {
            sb.append(sb.isEmpty() ? "" : ", ").append("totem");
        }
        if (reach == Reach.ASSIST) {
            if (parkour) {
                sb.append(sb.isEmpty() ? "" : ", ").append("parkour");
            }
            if (blocks) {
                sb.append(sb.isEmpty() ? "" : ", ").append("parkour blocks");
            }
        }
        return sb.isEmpty() ? "On, but no reflex is allowed" : (reach == Reach.DANGER_ONLY ? "Danger only: "
                : "Watching: ") + sb;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice(K_REACH, "How far it may go", Reach.ASSIST, "",
                        "DANGER_ONLY: steps in only when you are about to get hurt (a fall). ASSIST: also helps while"
                                + " you move (parkour timing, a block mid-jump). Your own keys and mouse always win."),
                SettingSpec.bool(K_CLUTCH, "Fall clutch", true, "Reflexes",
                        "A fall that would hurt: look down and clutch with water, powder snow, hay, slime or cobweb"
                                + " from the hotbar (Movement Assist's clutch settings apply)."),
                SettingSpec.bool(K_TOTEM, "Keep a totem in the offhand", true, "Reflexes",
                        "An empty offhand is refilled with a totem of undying from your inventory (one inventory click,"
                                + " like a player). Single-player and allow-listed servers only."),
                SettingSpec.bool(K_PARKOUR, "Parkour timing", true, "Reflexes",
                        "While you run forward: jump on the last tick before an edge, steer in the air toward a safe"
                                + " landing. ASSIST only."),
                SettingSpec.bool(K_BLOCKS, "Parkour blocks", true, "Reflexes",
                        "Mid-jump, place one block when it keeps the run going (uses hotbar blocks). ASSIST only."));
    }
}
