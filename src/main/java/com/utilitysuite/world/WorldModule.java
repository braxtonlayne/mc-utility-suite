package com.utilitysuite.world;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.render.RenderContext;

import java.util.List;

/**
 * Shared plumbing for the world modules: enabled flag, settings read from the active profile with the defaults taken
 * from the module's own {@link SettingSpec}s (one source of truth), and the two render phases of the shared world
 * renderer. Rendering is skipped by the renderer while a module is disabled or Clean-Screen is on.
 */
public abstract class WorldModule implements IModule, IConfigurable {

    private final String id;
    private final String displayName;
    private boolean enabled;

    protected WorldModule(String id, String displayName, boolean enabledByDefault) {
        this.id = id;
        this.displayName = displayName;
        this.enabled = enabledByDefault;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.WORLD;
    }

    @Override
    public final boolean isEnabled() {
        return enabled;
    }

    @Override
    public final void setEnabled(boolean on) {
        if (enabled == on) {
            return;
        }
        enabled = on;
        if (on) {
            onEnable();
        } else {
            onDisable();
        }
    }

    /** Flips the module and records the choice in the active profile (quick-toggle keys). */
    public void toggleFromKey() {
        setEnabled(!enabled);
        IModState s = Services.modState();
        s.profile().set(id, "enabled", enabled);
        s.markDirty();
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public void onSettingChanged(String key) {
        readSettings(Services.modState().profile());
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        readSettings(profile);
    }

    /** Re-reads every setting. Not a hot path. */
    protected abstract void readSettings(ProfileConfig p);

    /** Category bits this module needs from the shared scanner (0 = none). Only asked while enabled. */
    public int scanMask() {
        return 0;
    }

    /** Line phase of the shared renderer (enabled, not Clean-Screen). Zero allocation. */
    public void renderLines(RenderContext ctx) {
    }

    /** Label phase, after every module's lines were flushed. Zero allocation. */
    public void renderLabels(RenderContext ctx) {
    }

    // ---- settings helpers ---------------------------------------------------------------------------------------

    private SettingSpec spec(String key) {
        List<SettingSpec> specs = settings();
        for (SettingSpec s : specs) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException(id + " has no setting " + key);
    }

    protected boolean bool(ProfileConfig p, String key) {
        return p.getBool(id, key, (Boolean) spec(key).defaultValue());
    }

    protected int integer(ProfileConfig p, String key) {
        SettingSpec s = spec(key);
        int v = p.getInt(id, key, ((Number) s.defaultValue()).intValue());
        return (int) Math.max(s.min(), Math.min(s.max(), v));
    }

    protected double decimal(ProfileConfig p, String key) {
        SettingSpec s = spec(key);
        double v = p.getDouble(id, key, ((Number) s.defaultValue()).doubleValue());
        return Math.max(s.min(), Math.min(s.max(), v));
    }

    protected String text(ProfileConfig p, String key) {
        return p.getString(id, key, (String) spec(key).defaultValue());
    }

    protected <E extends Enum<E>> E choice(ProfileConfig p, String key, Class<E> type) {
        String def = (String) spec(key).defaultValue();
        String v = p.getString(id, key, def);
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            return Enum.valueOf(type, def);
        }
    }

    /** True while overlays must not draw (Clean-Screen). */
    protected static boolean cleanScreen() {
        IModState s = Services.modState();
        return s != null && s.isCleanScreen();
    }
}
