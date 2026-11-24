package com.utilitysuite.core.config;

import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.ProfileConfig;

/**
 * Global mod state and profile lifecycle. Pure Java. Client thread only.
 * <ul>
 *   <li>{@code global.json} is loaded at startup and holds suite-wide settings under the {@value #CORE} object
 *       (clean screen, override pause, mouse threshold, camera speed).</li>
 *   <li>The active profile is loaded on login ({@link #loadProfile}) and saved on logout ({@link #unloadProfile}).
 *       While no world is loaded, {@link #profile()} is the global profile.</li>
 *   <li>{@link #markDirty()} schedules a debounced save: {@value #SAVE_DELAY_TICKS} ticks after the last change both
 *       profiles are snapshotted and written off-thread.</li>
 * </ul>
 */
public final class ModState implements IModState {

    /** Settings object of the core inside {@code global.json}. */
    public static final String CORE = "core";
    public static final String KEY_CLEAN_SCREEN = "clean_screen";
    public static final String KEY_OVERRIDE_PAUSE_TICKS = "manual_override_pause_ticks";
    public static final String KEY_MOUSE_THRESHOLD_PX = "mouse_override_threshold_px";
    public static final String KEY_CAMERA_SPEED = "camera_speed_deg_per_sec";

    static final int SAVE_DELAY_TICKS = 40;

    private final ConfigStore store;
    private final IActionArbiter arbiter;
    private final ModuleRegistry modules;
    private ProfileConfig global;
    private ProfileConfig active;
    private boolean cleanScreen;
    private int saveCountdown;
    private Runnable onCoreSettingsChanged = () -> { };

    public ModState(ConfigStore store, IActionArbiter arbiter, ModuleRegistry modules) {
        this.store = store;
        this.arbiter = arbiter;
        this.modules = modules;
        this.global = new ProfileConfig(ProfileIds.GLOBAL, null);
    }

    /** Called after the global profile (re)loads so the core can re-read its settings. */
    public void setCoreSettingsListener(Runnable listener) {
        this.onCoreSettingsChanged = listener == null ? () -> { } : listener;
    }

    public void loadGlobal() {
        global = store.load(ProfileIds.GLOBAL);
        cleanScreen = global.getBool(CORE, KEY_CLEAN_SCREEN, false);
        onCoreSettingsChanged.run();
    }

    /** Login: loads the world/server profile and applies module enabled states and settings. */
    public void loadProfile(String profileId) {
        loadProfile(profileId, null);
    }

    /**
     * As {@link #loadProfile(String)}; {@code beforeApply} (may be null) edits the loaded profile before the modules
     * read it (the per-server legit level).
     */
    public void loadProfile(String profileId, java.util.function.Consumer<ProfileConfig> beforeApply) {
        if (active != null) {
            unloadProfile();
        }
        active = store.load(profileId);
        if (beforeApply != null) {
            beforeApply.accept(active);
        }
        modules.applyProfile(active);
    }

    /** Logout: captures module states and writes both profiles synchronously. */
    public void unloadProfile() {
        saveCountdown = 0;
        if (active != null) {
            modules.captureEnabledStates(active);
            store.writeNow(active);
            active = null;
        }
        store.writeNow(global);
    }

    /** Debounce countdown; call once per client tick. */
    public void tick() {
        if (saveCountdown > 0 && --saveCountdown == 0) {
            saveNowAsync();
        }
    }

    /** Snapshots both profiles now and writes them off-thread. */
    public void saveNowAsync() {
        if (active != null) {
            modules.captureEnabledStates(active);
            store.writeAsync(active);
        }
        store.writeAsync(global);
    }

    public boolean hasActiveProfile() {
        return active != null;
    }

    @Override
    public boolean isCleanScreen() {
        return cleanScreen;
    }

    @Override
    public void setCleanScreen(boolean value) {
        if (cleanScreen != value) {
            cleanScreen = value;
            global.set(CORE, KEY_CLEAN_SCREEN, value);
            markDirty();
        }
    }

    @Override
    public boolean isAutomationStopped() {
        return arbiter.isAutomationStopped();
    }

    @Override
    public ProfileConfig profile() {
        return active != null ? active : global;
    }

    @Override
    public ProfileConfig global() {
        return global;
    }

    @Override
    public void markDirty() {
        saveCountdown = SAVE_DELAY_TICKS;
    }
}
