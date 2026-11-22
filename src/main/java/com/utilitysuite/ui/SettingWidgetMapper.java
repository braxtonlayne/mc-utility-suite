package com.utilitysuite.ui;

import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.ui.kit.WidgetKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping from {@link SettingSpec}s to widget kinds, slider math, number parsing/formatting and section layout.
 * The Minecraft widgets in {@link SettingsRenderer} only call into this, so the behavior is unit tested.
 */
public final class SettingWidgetMapper {

    /** Widget used for a setting. */
    public enum Kind {
        /** BOOL: ON/OFF button. */
        TOGGLE,
        /** INT/DOUBLE with a sensible finite range: slider snapped to {@link #step}. */
        SLIDER,
        /** INT/DOUBLE without a usable range: number edit box, clamped to bounds. */
        NUMBER_FIELD,
        /** ENUM: cycle button over {@link SettingSpec#options()}. */
        CYCLE,
        /** STRING (or an ENUM without options): edit box. */
        TEXT_FIELD
    }

    /** INT ranges wider than this get a number field instead of a slider (a slider would be too coarse). */
    public static final int MAX_SLIDER_STEPS = 1000;

    /** A settings section: one group heading, basic or inside the Advanced drawer. */
    public record Section(String group, boolean advanced, List<SettingSpec> specs) {
        public Section {
            specs = List.copyOf(specs);
        }
    }

    private SettingWidgetMapper() {
    }

    public static Kind kindOf(SettingSpec s) {
        return switch (s.type()) {
            case BOOL -> Kind.TOGGLE;
            case ENUM -> s.options().isEmpty() ? Kind.TEXT_FIELD : Kind.CYCLE;
            case STRING -> Kind.TEXT_FIELD;
            case INT -> hasRange(s) && (s.max() - s.min()) <= MAX_SLIDER_STEPS ? Kind.SLIDER : Kind.NUMBER_FIELD;
            case DOUBLE -> hasRange(s) ? Kind.SLIDER : Kind.NUMBER_FIELD;
        };
    }

    private static boolean hasRange(SettingSpec s) {
        return Double.isFinite(s.min()) && Double.isFinite(s.max()) && s.max() > s.min();
    }

    /** Slider/number step: 1 for INT; for DOUBLE a "nice" 1/2/5 x 10^k close to range/100. */
    public static double step(SettingSpec s) {
        if (s.type() == SettingSpec.Type.INT) {
            return 1.0;
        }
        if (!hasRange(s)) {
            return 0.01;
        }
        double raw = (s.max() - s.min()) / 100.0;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double nice = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
        return nice * mag;
    }

    public static double clamp(SettingSpec s, double v) {
        if (Double.isNaN(v)) {
            return hasRange(s) ? s.min() : 0.0;
        }
        if (hasRange(s)) {
            v = Math.max(s.min(), Math.min(s.max(), v));
        }
        return s.type() == SettingSpec.Type.INT ? Math.round(v) : v;
    }

    /** Stable animation key of the widget of a setting: module id + setting key hash, the same on every rebuild. */
    public static long widgetKey(String moduleId, SettingSpec s) {
        return WidgetKeys.setting(moduleId, s.key());
    }

    /** Slider position 0..1 of the spec's default value (the tick mark), or -1 when it has none. */
    public static double defaultPosition(SettingSpec s) {
        if (!hasRange(s) || !(s.defaultValue() instanceof Number n)) {
            return -1.0;
        }
        return toSliderPosition(s, n.doubleValue());
    }

    /** Value → slider position 0..1. */
    public static double toSliderPosition(SettingSpec s, double value) {
        if (!hasRange(s)) {
            return 0.0;
        }
        return (clamp(s, value) - s.min()) / (s.max() - s.min());
    }

    /** Slider position 0..1 → value snapped to {@link #step} and clamped (rounded for INT). */
    public static double fromSliderPosition(SettingSpec s, double position) {
        if (!hasRange(s)) {
            return clamp(s, 0.0);
        }
        double p = Math.max(0.0, Math.min(1.0, position));
        double raw = s.min() + p * (s.max() - s.min());
        double st = step(s);
        double snapped = s.min() + Math.round((raw - s.min()) / st) * st;
        return clamp(s, roundTo(snapped, decimals(st)));
    }

    /** Parses a number field; null when not a number. Clamped to the bounds; INT rounds. */
    public static Double parseNumber(SettingSpec s, String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().replace(',', '.');
        if (t.isEmpty() || t.equals("-") || t.equals(".")) {
            return null;
        }
        try {
            double v = Double.parseDouble(t);
            if (!Double.isFinite(v)) {
                return null;
            }
            return clamp(s, v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Display text: integers without decimals, doubles with as many decimals as the step needs. */
    public static String format(SettingSpec s, double v) {
        if (s.type() == SettingSpec.Type.INT) {
            return Long.toString(Math.round(v));
        }
        int d = Math.max(decimals(step(s)), 0);
        String out = String.format(Locale.ROOT, "%." + d + "f", v);
        return out;
    }

    /** Next ENUM option after {@code current} (wraps; unknown current → first). */
    public static String nextOption(SettingSpec s, String current) {
        List<String> o = s.options();
        if (o.isEmpty()) {
            return current;
        }
        int i = o.indexOf(current);
        return o.get((i + 1) % o.size());
    }

    /** Readable label for an enum constant: "OBSERVED_ONLY" → "Observed only". */
    public static String prettyEnum(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String lower = name.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Groups settings into sections: basic sections first (top-level group "" first, then groups in order of first
     * appearance), then the advanced sections (same ordering) that go into the collapsed drawer.
     */
    public static List<Section> layout(List<SettingSpec> specs) {
        Map<String, List<SettingSpec>> basic = new LinkedHashMap<>();
        Map<String, List<SettingSpec>> advanced = new LinkedHashMap<>();
        basic.put("", new ArrayList<>());
        advanced.put("", new ArrayList<>());
        for (SettingSpec s : specs) {
            (s.advanced() ? advanced : basic).computeIfAbsent(s.group(), k -> new ArrayList<>()).add(s);
        }
        List<Section> out = new ArrayList<>();
        basic.forEach((g, l) -> {
            if (!l.isEmpty()) {
                out.add(new Section(g, false, l));
            }
        });
        advanced.forEach((g, l) -> {
            if (!l.isEmpty()) {
                out.add(new Section(g, true, l));
            }
        });
        return out;
    }

    /** True when any setting is in the Advanced drawer. */
    public static boolean hasAdvanced(List<SettingSpec> specs) {
        for (SettingSpec s : specs) {
            if (s.advanced()) {
                return true;
            }
        }
        return false;
    }

    private static int decimals(double step) {
        int d = 0;
        double v = step;
        while (d < 6 && Math.abs(v - Math.rint(v)) > 1e-9) {
            v *= 10;
            d++;
        }
        return d;
    }

    private static double roundTo(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
