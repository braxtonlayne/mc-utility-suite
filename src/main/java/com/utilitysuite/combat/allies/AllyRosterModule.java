package com.utilitysuite.combat.allies;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;
import java.util.UUID;

/**
 * The Ally Roster as a module: loads the per-world and global lists when a profile loads, saves on every change, and
 * gates the middle-click toggle. The roster itself is always enforced by the combat code, whether this module is
 * enabled or not; disabling it only turns off the middle-click shortcut. Also the public API for the Combat tab.
 */
public final class AllyRosterModule implements IModule, IConfigurable {

    public static final String ID = "ally_roster";
    public static final String MIDDLE_CLICK = "middle_click_toggle";

    private final AllyRoster roster = new AllyRoster();
    private final AllyRosterStore store;
    private boolean enabled = true;
    private boolean middleClick = true;
    private boolean globalLoaded;
    private String profileId;

    public AllyRosterModule(AllyRosterStore store) {
        this.store = store;
        roster.setChangeListener(this::save);
    }

    public AllyRoster roster() {
        return roster;
    }

    public AllyRosterStore store() {
        return store;
    }

    /** Middle-click entry point: toggles the player on the current world's list. True when now on the roster. */
    public boolean togglePlayer(UUID uuid, String name) {
        return roster.toggle(uuid, name);
    }

    public boolean middleClickEnabled() {
        return enabled && middleClick;
    }

    private void save() {
        store.saveAsync(roster, profileId);
    }

    // ---- IModule --------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.COMBAT;
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
        // Event driven; nothing per tick.
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        if (!globalLoaded) {
            roster.loadGlobal(store.loadGlobal());
            globalLoaded = true;
        }
        profileId = profile.profileId();
        roster.loadWorld(store.loadProfile(profileId));
        middleClick = profile.getBool(ID, MIDDLE_CLICK, true);
    }

    @Override
    public void onWorldUnload() {
        store.flush();
    }

    // ---- IConfigurable --------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Ally Roster";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.bool(MIDDLE_CLICK, "Middle-click toggles players", true, "",
                "Middle-click a player you are looking at to add or remove them as an ally."));
    }

    @Override
    public void onSettingChanged(String key) {
        if (MIDDLE_CLICK.equals(key)) {
            middleClick = Services.modState().profile().getBool(ID, MIDDLE_CLICK, true);
        }
    }

    /** Explicit setter for the Combat tab / tests. */
    public void setMiddleClick(boolean value) {
        this.middleClick = value;
    }
}
