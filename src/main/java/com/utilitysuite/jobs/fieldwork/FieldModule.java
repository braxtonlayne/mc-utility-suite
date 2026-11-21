package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;

/**
 * Shared plumbing of the field modules (Auto-Torch, Lava Plug, Vein Work, Crop Work): off by default, JOBS tab, one
 * {@link FieldHands}, and the legit/relaxed switch every one of them reads. {@code relaxed} is the explicit less-legit
 * setting: silent rotation, no line-of-sight requirement. Legit (default): smoothed visible look, line of sight.
 */
abstract class FieldModule implements IModule, IConfigurable {

    static final String KEY_RELAXED = "relaxed";

    protected final String id;
    protected final FieldHands hands;
    protected boolean enabled;
    protected boolean relaxed;

    FieldModule(String id, ControlPriority priority) {
        this.id = id;
        this.hands = new FieldHands(id, priority);
    }

    static SettingSpec relaxedSpec() {
        return SettingSpec.bool(KEY_RELAXED, "Relaxed (less legit)", false, "Tuning",
                "Silent rotation and no line-of-sight check. Off: smoothed visible turns, one block at a time")
                .asAdvanced();
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.JOBS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (this.enabled && !enabled) {
            stopWork();
        }
        this.enabled = enabled;
    }

    @Override
    public void onStopAll() {
        stopWork();
    }

    @Override
    public void onWorldUnload() {
        stopWork();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        readSettings(profile);
    }

    @Override
    public void onSettingChanged(String key) {
        readSettings(Services.modState().profile());
    }

    protected void readSettings(ProfileConfig p) {
        relaxed = p.getBool(id, KEY_RELAXED, false);
    }

    /** Drops the current run and every lease; stays enabled. */
    protected void stopWork() {
        hands.release();
    }
}
