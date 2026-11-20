package com.utilitysuite.core.api;

import java.util.List;

/**
 * Implemented by modules that expose settings to the UI shell. The shell renders {@link #settings()} in the module's
 * tab, writes changed values into {@code Services.modState().profile().module(id())}, calls
 * {@link #onSettingChanged(String)} and marks the profile dirty.
 */
public interface IConfigurable {

    /** Display name in the menu, e.g. "Survival Autopilot". */
    String displayName();

    List<SettingSpec> settings();

    /** A setting was changed by the user; re-read it from the active profile. */
    void onSettingChanged(String key);
}
