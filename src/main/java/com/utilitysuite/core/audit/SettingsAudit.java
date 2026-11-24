package com.utilitysuite.core.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Diagnostics only: checks that every menu toggle and setting of a module actually does something. For each
 * {@link SettingSpec} it writes a non-default value the same way the settings UI does (into the active profile, then
 * {@link IConfigurable#onSettingChanged}), and compares a {@link StateFingerprint} of the module before and after.
 * A setting whose change leaves the module's state untouched is reported as dead. It also checks that restoring the
 * default restores the state, that the profile-load path reads the value too, that every ENUM option is distinct
 * from the default, that the spec itself is well formed, and that the module's on/off toggle works. Engine free:
 * the unit test and the in-game runner both drive it.
 */
public final class SettingsAudit {

    /** One problem found. */
    public record Finding(String moduleId, String key, String problem) {
        @Override
        public String toString() {
            return moduleId + (key.isEmpty() ? "" : "." + key) + ": " + problem;
        }
    }

    /** Result of auditing a set of modules. */
    public record Report(int modules, int settings, int variantsTried, List<Finding> findings) {
        public Report {
            findings = List.copyOf(findings);
        }

        public boolean clean() {
            return findings.isEmpty();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Settings audit: ").append(modules).append(" modules, ").append(settings).append(" settings, ")
                    .append(variantsTried).append(" values tried, ").append(findings.size()).append(" problem(s)");
            for (Finding f : findings) {
                sb.append("\n  - ").append(f);
            }
            return sb.toString();
        }
    }

    /**
     * A module to audit. {@code state} is what to fingerprint (normally the module itself, plus any object its settings
     * are written into, such as a shared theme); {@code profile} is the profile the UI writes this module's settings
     * to; {@code afterChange} runs after each change (the in-game runner ticks the module there).
     */
    public record Target(IModule module, IConfigurable config, Supplier<ProfileConfig> profile,
                         Supplier<Object> state, Consumer<String> afterChange) {
        public static Target of(IModule module, Supplier<ProfileConfig> profile) {
            List<Class<?>> holders = staticHolders(module.id());
            Supplier<Object> state = holders.isEmpty() ? () -> module : () -> {
                List<Object> roots = new ArrayList<>();
                roots.add(module);
                roots.addAll(holders);
                return roots.toArray();
            };
            return new Target(module, (IConfigurable) module, profile, state, key -> { });
        }

        public Target withState(Supplier<Object> s) {
            return new Target(module, config, profile, s, afterChange);
        }

        public Target afterChange(Consumer<String> c) {
            return new Target(module, config, profile, state, c);
        }
    }

    /**
     * Modules whose settings are written into static state elsewhere (class names, so core does not import the UI):
     * the fingerprint includes those classes' static fields.
     */
    private static final Map<String, List<String>> STATIC_HOLDERS = Map.of(
            "appearance", List.of("com.utilitysuite.ui.holo.HoloTheme"));

    /**
     * Runtime counters (not settings) that a setting change legitimately moves; ignored in the comparisons.
     * movement_assist: switching the intent assist off clears the target, which counts one missed-target tick.
     */
    private static final Map<String, Set<String>> RUNTIME_PATHS = Map.of(
            "movement_assist", Set.of("brain.rawMissTicks"));

    static List<Class<?>> staticHolders(String moduleId) {
        List<Class<?>> out = new ArrayList<>();
        for (String name : STATIC_HOLDERS.getOrDefault(moduleId, List.of())) {
            try {
                out.add(Class.forName(name));
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException("settings audit: static holder " + name + " is gone", e);
            }
        }
        return out;
    }

    /** Settings that are known not to change module state, with the reason (e.g. read live from the profile). */
    private final Map<String, String> exempt;
    /** {@code "id"} skips a module, {@code "id#toggle"} its on/off check, {@code "id.key"} one setting, {@code "id.*"} all its settings. */
    private final Set<String> skip;
    private final Function<Object, Map<String, String>> fingerprint;

    /**
     * @param exempt {@code "moduleId.key" -> reason}; an exempt setting is still exercised (it must not throw) but a
     *               missing state change is not reported. An exemption that is no longer needed is reported.
     */
    public SettingsAudit(Map<String, String> exempt) {
        this(exempt, Set.of());
    }

    /**
     * @param skip checks this environment cannot run (the in-game audit runs them): {@code "id"} skips a module,
     *             {@code "id#toggle"} its on/off check, {@code "id.key"} one setting
     */
    public SettingsAudit(Map<String, String> exempt, Set<String> skip) {
        this(exempt, skip, StateFingerprint::of);
    }

    SettingsAudit(Map<String, String> exempt, Set<String> skip, Function<Object, Map<String, String>> fingerprint) {
        this.exempt = Map.copyOf(exempt);
        this.skip = Set.copyOf(skip);
        this.fingerprint = fingerprint;
    }

    public Report run(List<Target> targets) {
        List<Finding> findings = new ArrayList<>();
        Set<String> exemptButChanged = new HashSet<>();
        int settings = 0;
        int variants = 0;
        Set<String> ids = new HashSet<>();
        for (Target t : targets) {
            String id = t.module().id();
            if (!ids.add(id)) {
                findings.add(new Finding(id, "", "duplicate module id"));
            }
            if (skip.contains(id)) {
                continue;
            }
            // Infrastructure modules (CORE) are always on and have no switch in the menu.
            if (t.module().category() != ModuleCategory.CORE && !skip.contains(id + "#toggle")) {
                checkToggle(t, findings);
            }
            Set<String> keys = new HashSet<>();
            for (SettingSpec s : t.config().settings()) {
                settings++;
                if (!keys.add(s.key())) {
                    findings.add(new Finding(id, s.key(), "duplicate setting key"));
                    continue;
                }
                String specProblem = specProblem(s);
                if (specProblem != null) {
                    findings.add(new Finding(id, s.key(), specProblem));
                    continue;
                }
                if (skip.contains(id + "." + s.key()) || skip.contains(id + ".*")) {
                    continue;
                }
                variants += checkSetting(t, s, findings, exemptButChanged);
            }
        }
        for (String k : exemptButChanged) {
            findings.add(new Finding(k.substring(0, k.indexOf('.')), k.substring(k.indexOf('.') + 1),
                    "exempt (" + exempt.get(k) + ") but it does change module state now: remove the exemption"));
        }
        return new Report(targets.size(), settings, variants, findings);
    }

    private static void checkToggle(Target t, List<Finding> findings) {
        IModule m = t.module();
        boolean was = m.isEnabled();
        try {
            m.setEnabled(!was);
            if (m.isEnabled() == was) {
                findings.add(new Finding(m.id(), "", "on/off toggle does nothing (isEnabled did not change)"));
            }
            t.afterChange().accept("enabled");
            m.setEnabled(was);
            if (m.isEnabled() != was) {
                findings.add(new Finding(m.id(), "", "on/off toggle does not switch back"));
            }
        } catch (RuntimeException e) {
            findings.add(new Finding(m.id(), "", "on/off toggle threw " + describe(e)));
            safely(() -> m.setEnabled(was));
        }
    }

    /** Problems with the spec itself, or null. */
    static String specProblem(SettingSpec s) {
        if (s.key() == null || s.key().isBlank()) {
            return "blank key";
        }
        if (s.label() == null || s.label().isBlank()) {
            return "blank label";
        }
        Object d = s.defaultValue();
        return switch (s.type()) {
            case BOOL -> d instanceof Boolean ? null : "BOOL default is not a boolean: " + d;
            case INT, DOUBLE -> {
                if (!(d instanceof Number n)) {
                    yield "numeric default is not a number: " + d;
                }
                if (!(s.max() > s.min())) {
                    yield "range is empty: [" + s.min() + ", " + s.max() + "]";
                }
                double v = n.doubleValue();
                yield v < s.min() || v > s.max() ? "default " + v + " outside [" + s.min() + ", " + s.max() + "]" : null;
            }
            case ENUM -> {
                if (s.options().size() < 2) {
                    yield "ENUM with fewer than two options: " + s.options();
                }
                yield s.options().contains(String.valueOf(d)) ? null : "ENUM default " + d + " not in " + s.options();
            }
            case STRING -> d == null || d instanceof String ? null : "STRING default is not a string: " + d;
        };
    }

    /** Returns how many values were tried. */
    private int checkSetting(Target t, SettingSpec s, List<Finding> findings, Set<String> exemptButChanged) {
        String id = t.module().id();
        String exemptKey = id + "." + s.key();
        boolean isExempt = exempt.containsKey(exemptKey);
        ProfileConfig profile = t.profile().get();
        JsonObject obj = profile.module(id);
        JsonElement original = obj.has(s.key()) ? obj.get(s.key()).deepCopy() : null;
        int tried = 0;
        try {
            // Baseline: the default value, applied through the same path as the UI.
            write(obj, s, defaultString(s));
            if (!apply(t, s.key(), findings, "default")) {
                return tried;
            }
            Map<String, String> base = fingerprint.apply(t.state().get());
            // Applying the same default again shows which fields move on their own (counters bumped by every
            // re-read): those are noise, not effects of the value.
            if (!apply(t, s.key(), findings, "default")) {
                return tried;
            }
            Set<String> noise = changedPaths(base, fingerprint.apply(t.state().get()));
            noise.addAll(RUNTIME_PATHS.getOrDefault(id, Set.of()));
            base = without(base, noise);

            // Free text has no option list: try typical shapes (ids, lists, coordinates) until one takes effect.
            boolean anyOf = s.type() == SettingSpec.Type.STRING;
            String working = null;
            List<String> values = variants(s);
            for (String value : values) {
                tried++;
                write(obj, s, value);
                if (!apply(t, s.key(), findings, value)) {
                    return tried;
                }
                Map<String, String> changed = without(fingerprint.apply(t.state().get()), noise);
                boolean differs = !changed.equals(base);
                if (differs) {
                    if (working == null) {
                        working = value;
                    }
                    if (isExempt) {
                        exemptButChanged.add(exemptKey);
                    }
                } else if (!anyOf && !isExempt) {
                    findings.add(new Finding(id, s.key(), "setting it to " + value
                            + " changes nothing in the module (dead setting, or read somewhere the audit cannot see)"));
                }
                // Back to the default: the state must come back too (no one-way latches).
                write(obj, s, defaultString(s));
                if (!apply(t, s.key(), findings, "default again")) {
                    return tried;
                }
                Map<String, String> restored = without(fingerprint.apply(t.state().get()), noise);
                if (!restored.equals(base)) {
                    findings.add(new Finding(id, s.key(), "setting " + value + " then back to the default does not"
                            + " restore the module: " + StateFingerprint.diff(base, restored, 3)));
                }
                if (anyOf && differs) {
                    break;
                }
            }
            if (anyOf && working == null && !isExempt && !values.isEmpty()) {
                findings.add(new Finding(id, s.key(), "none of " + values + " changes anything in the module (dead"
                        + " setting, or read somewhere the audit cannot see)"));
            }
            String loadValue = anyOf ? working : values.isEmpty() ? null : values.get(0);
            if (loadValue != null && !isExempt) {
                checkProfileLoad(t, s, obj, findings, loadValue);
            }
        } finally {
            if (original == null) {
                obj.remove(s.key());
            } else {
                obj.add(s.key(), original);
            }
            apply(t, s.key(), new ArrayList<>(), "restore");
        }
        return tried;
    }

    private static Set<String> changedPaths(Map<String, String> a, Map<String, String> b) {
        Set<String> out = new HashSet<>();
        for (String k : a.keySet()) {
            if (!Objects.equals(a.get(k), b.get(k))) {
                out.add(k);
            }
        }
        for (String k : b.keySet()) {
            if (!a.containsKey(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static Map<String, String> without(Map<String, String> m, Set<String> noise) {
        if (noise.isEmpty()) {
            return m;
        }
        Map<String, String> out = new java.util.TreeMap<>(m);
        out.keySet().removeAll(noise);
        return out;
    }

    /** The value must also be picked up when a profile is loaded (joining a world), not only on a menu change. */
    private void checkProfileLoad(Target t, SettingSpec s, JsonObject obj, List<Finding> findings, String value) {
        String id = t.module().id();
        ProfileConfig profile = t.profile().get();
        try {
            write(obj, s, defaultString(s));
            t.module().onProfileLoaded(profile);
            Map<String, String> base = fingerprint.apply(t.state().get());
            write(obj, s, value);
            t.module().onProfileLoaded(profile);
            Map<String, String> loaded = fingerprint.apply(t.state().get());
            if (loaded.equals(base)) {
                findings.add(new Finding(id, s.key(), "works from the menu but is ignored when the profile loads"
                        + " (onProfileLoaded does not read it)"));
            }
            write(obj, s, defaultString(s));
            t.module().onProfileLoaded(profile);
        } catch (RuntimeException e) {
            findings.add(new Finding(id, s.key(), "onProfileLoaded threw " + describe(e)));
        }
    }

    private static boolean apply(Target t, String key, List<Finding> findings, String value) {
        try {
            t.config().onSettingChanged(key);
            t.afterChange().accept(key);
            return true;
        } catch (RuntimeException e) {
            findings.add(new Finding(t.module().id(), key, "threw when set to " + value + ": " + describe(e)));
            return false;
        }
    }

    /** Typical free-text shapes: empty, an item id, a slot list, a prefix, a seed, a box, a key=value list. */
    static final List<String> STRING_SAMPLES = List.of("", "minecraft:stone", "1,2,9", "!", "12345",
            "0 60 0 10 70 10", "minecraft:stone=2", "diamond");

    /** Non-default values to try: the flipped BOOL, both ends of a numeric range, every other ENUM option. */
    public static List<String> variants(SettingSpec s) {
        String def = defaultString(s);
        List<String> out = new ArrayList<>();
        switch (s.type()) {
            case BOOL -> out.add(Boolean.toString(!Boolean.parseBoolean(def)));
            case INT, DOUBLE -> {
                for (double v : new double[] {s.max(), s.min()}) {
                    String str = format(s, v);
                    if (!str.equals(format(s, Double.parseDouble(def))) && !out.contains(str)) {
                        out.add(str);
                    }
                }
            }
            case ENUM -> {
                for (String o : s.options()) {
                    if (!o.equals(def)) {
                        out.add(o);
                    }
                }
            }
            case STRING -> {
                for (String v : STRING_SAMPLES) {
                    if (!v.equals(def)) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    public static String defaultString(SettingSpec s) {
        Object d = s.defaultValue();
        if (s.type() == SettingSpec.Type.INT || s.type() == SettingSpec.Type.DOUBLE) {
            return format(s, d instanceof Number n ? n.doubleValue() : s.min());
        }
        return d == null ? "" : String.valueOf(d);
    }

    private static String format(SettingSpec s, double v) {
        return s.type() == SettingSpec.Type.INT ? Long.toString(Math.round(v)) : Double.toString(v);
    }

    /** Writes like {@code SettingsAccess}: typed JSON (INT as an integer). */
    public static void write(JsonObject obj, SettingSpec s, String value) {
        switch (s.type()) {
            case BOOL -> obj.addProperty(s.key(), Boolean.parseBoolean(value));
            case INT -> obj.addProperty(s.key(), Integer.parseInt(value));
            case DOUBLE -> obj.addProperty(s.key(), Double.parseDouble(value));
            case ENUM, STRING -> obj.addProperty(s.key(), Objects.requireNonNull(value));
        }
    }

    private static String describe(Throwable e) {
        StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")")
                + (top == null ? "" : " at " + top.getClassName().substring(top.getClassName().lastIndexOf('.') + 1)
                + ":" + top.getLineNumber());
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // best effort while recovering from an earlier failure
        }
    }
}
