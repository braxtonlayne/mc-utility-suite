package com.utilitysuite.ui;

import com.google.gson.JsonElement;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.config.ConfigUndoStack;

import java.util.function.Consumer;

/**
 * The menu's 10-step setting undo: every write through {@link SettingsAccess} is recorded (old value, new value and the
 * change callback); {@link #undo()} (menu Undo button, Undo key, palette action) writes the old value back into the
 * profile it came from, calls the same change callback and schedules the save. A change in a profile that is no longer
 * loaded (world left) is dropped rather than applied to the wrong profile. Client thread only.
 */
public final class ConfigUndo {

    public static final int STEPS = 10;
    private static final ConfigUndoStack STACK = new ConfigUndoStack(STEPS);

    private ConfigUndo() {
    }

    /** Recorded by {@link SettingsAccess} after each write. */
    static void record(ProfileConfig profile, String moduleId, String key, JsonElement before, JsonElement after,
                       Consumer<String> onChanged) {
        STACK.record(profile.profileId(), moduleId, key, before, after, onChanged);
    }

    public static int size() {
        return STACK.size();
    }

    /** Label of the change Undo would revert ("module.key"), or null when there is none. */
    public static String peekLabel() {
        ConfigUndoStack.Entry e = STACK.peek();
        return e == null ? null : e.moduleId() + "." + e.key();
    }

    public static void clear() {
        STACK.clear();
    }

    /** Reverts the latest change. Returns a status line for a toast. */
    @SuppressWarnings("unchecked")
    public static String undo() {
        IModState state = Services.modState();
        while (true) {
            ConfigUndoStack.Entry e = STACK.pop();
            if (e == null) {
                return "Nothing to undo";
            }
            ProfileConfig target = state.profile().profileId().equals(e.profileId()) ? state.profile()
                    : state.global().profileId().equals(e.profileId()) ? state.global() : null;
            if (target == null) {
                continue; // that profile is not loaded any more
            }
            ConfigUndoStack.restore(e, target.root());
            if (e.tag() instanceof Consumer<?> c) {
                ((Consumer<String>) c).accept(e.key());
            }
            state.markDirty();
            return "Undid " + e.moduleId() + "." + e.key() + " (" + STACK.size() + " more)";
        }
    }
}
