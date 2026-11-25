package com.utilitysuite.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;

import java.util.function.Consumer;

/**
 * Reads and writes one module's settings in a {@link ProfileConfig} for the generic settings UI: values fall back to
 * the spec's default (also when the stored JSON has the wrong type), writes are typed (INT stored as an integer),
 * and every write calls the module's change hook and marks the profile dirty. Pure Java (Gson).
 */
public final class SettingsAccess {

    private final ProfileConfig profile;
    private final String moduleId;
    private final Consumer<String> onChanged;
    private final Runnable markDirty;

    /**
     * @param onChanged called with the setting key after each write ({@code IConfigurable.onSettingChanged})
     * @param markDirty schedules the save ({@code IModState.markDirty})
     */
    public SettingsAccess(ProfileConfig profile, String moduleId, Consumer<String> onChanged, Runnable markDirty) {
        this.profile = profile;
        this.moduleId = moduleId;
        this.onChanged = onChanged;
        this.markDirty = markDirty;
    }

    public String moduleId() {
        return moduleId;
    }

    private JsonElement raw(String key) {
        JsonObject o = profile.root().getAsJsonObject(moduleId);
        return o == null ? null : o.get(key);
    }

    public boolean getBool(SettingSpec s) {
        JsonElement e = raw(s.key());
        try {
            if (e != null && e.isJsonPrimitive()) {
                return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean()
                        : Boolean.parseBoolean(e.getAsString());
            }
        } catch (RuntimeException ignored) {
            // fall back to the default
        }
        return s.defaultValue() instanceof Boolean b && b;
    }

    public double getNumber(SettingSpec s) {
        JsonElement e = raw(s.key());
        try {
            if (e != null && e.isJsonPrimitive()) {
                return SettingWidgetMapper.clamp(s, e.getAsDouble());
            }
        } catch (RuntimeException ignored) {
            // fall back to the default
        }
        return s.defaultValue() instanceof Number n ? n.doubleValue() : (s.min() <= 0 && s.max() >= 0 ? 0 : s.min());
    }

    public String getString(SettingSpec s) {
        JsonElement e = raw(s.key());
        if (e != null && e.isJsonPrimitive()) {
            String v = e.getAsString();
            if (s.type() != SettingSpec.Type.ENUM || s.options().isEmpty() || s.options().contains(v)) {
                return v;
            }
        }
        if (s.defaultValue() != null) {
            return String.valueOf(s.defaultValue());
        }
        return s.options().isEmpty() ? "" : s.options().get(0);
    }

    public void setBool(SettingSpec s, boolean value) {
        JsonElement before = copy(raw(s.key()));
        profile.set(moduleId, s.key(), value);
        changed(s, before);
    }

    public void setNumber(SettingSpec s, double value) {
        JsonElement before = copy(raw(s.key()));
        double v = SettingWidgetMapper.clamp(s, value);
        if (s.type() == SettingSpec.Type.INT) {
            profile.set(moduleId, s.key(), (int) Math.round(v));
        } else {
            profile.set(moduleId, s.key(), v);
        }
        changed(s, before);
    }

    public void setString(SettingSpec s, String value) {
        JsonElement before = copy(raw(s.key()));
        profile.set(moduleId, s.key(), value == null ? "" : value);
        changed(s, before);
    }

    private static JsonElement copy(JsonElement e) {
        return e == null ? null : e.deepCopy();
    }

    private void changed(SettingSpec s, JsonElement before) {
        ConfigUndo.record(profile, moduleId, s.key(), before, raw(s.key()), onChanged);
        changed(s);
    }

    private void changed(SettingSpec s) {
        onChanged.accept(s.key());
        markDirty.run();
    }
}
