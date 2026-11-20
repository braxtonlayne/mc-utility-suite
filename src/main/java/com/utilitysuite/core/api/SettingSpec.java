package com.utilitysuite.core.api;

import java.util.List;

/**
 * Describes one user-facing setting of a module so the UI shell can render it generically. Values live in the active
 * {@link ProfileConfig} under {@code module(moduleId).get(key)}; the module reads them in
 * {@link IModule#onProfileLoaded} and whenever the UI calls {@link IConfigurable#onSettingChanged}.
 *
 * @param group     sub-section heading inside the module ("SafeWalk", "Advanced"); empty for the top level
 * @param advanced  hidden in a collapsed "Advanced" drawer by default
 * @param options   allowed values for {@link Type#ENUM}; empty otherwise
 * @param min       lower bound for INT/DOUBLE
 * @param max       upper bound for INT/DOUBLE
 */
public record SettingSpec(String key, String label, Type type, String group, boolean advanced, Object defaultValue,
                          double min, double max, List<String> options, String tooltip) {

    public enum Type { BOOL, INT, DOUBLE, ENUM, STRING }

    public SettingSpec {
        options = options == null ? List.of() : List.copyOf(options);
        group = group == null ? "" : group;
        tooltip = tooltip == null ? "" : tooltip;
    }

    public static SettingSpec bool(String key, String label, boolean def, String group, String tooltip) {
        return new SettingSpec(key, label, Type.BOOL, group, false, def, 0, 1, List.of(), tooltip);
    }

    public static SettingSpec integer(String key, String label, int def, int min, int max, String group, String tooltip) {
        return new SettingSpec(key, label, Type.INT, group, false, def, min, max, List.of(), tooltip);
    }

    public static SettingSpec decimal(String key, String label, double def, double min, double max, String group,
                                      String tooltip) {
        return new SettingSpec(key, label, Type.DOUBLE, group, false, def, min, max, List.of(), tooltip);
    }

    public static <E extends Enum<E>> SettingSpec choice(String key, String label, E def, String group, String tooltip) {
        List<String> names = java.util.Arrays.stream(def.getDeclaringClass().getEnumConstants()).map(Enum::name).toList();
        return new SettingSpec(key, label, Type.ENUM, group, false, def.name(), 0, 0, names, tooltip);
    }

    public static SettingSpec text(String key, String label, String def, String group, String tooltip) {
        return new SettingSpec(key, label, Type.STRING, group, false, def, 0, 0, List.of(), tooltip);
    }

    /** Copy marked as advanced (collapsed drawer). */
    public SettingSpec asAdvanced() {
        return new SettingSpec(key, label, type, group, true, defaultValue, min, max, options, tooltip);
    }
}
