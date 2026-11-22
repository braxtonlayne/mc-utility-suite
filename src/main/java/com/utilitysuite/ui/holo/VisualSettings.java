package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.EffectLevel;

import java.util.List;
import java.util.Locale;

/**
 * The "Appearance" card (rendered under Tools, stored in the profile like every other module): effect level, motion
 * level, accent preset, HUD opacity, HUD scale, font
 * face and the opt-in armed marker. It writes the {@link HoloTheme} fields when a profile loads and
 * when a setting changes; drawing code only reads them. Infrastructure, so it reports {@link ModuleCategory#CORE}
 * and is always on (no toggle, never in the enabled-module list). Pure Java.
 */
public final class VisualSettings implements IModule, IConfigurable {

    public static final String ID = "appearance";
    public static final String K_EFFECTS = "effects";
    public static final String K_MOTION = "motion";
    public static final String K_ACCENT = "accent";
    public static final String K_HUD_OPACITY = "hud_opacity";
    public static final String K_HUD_SCALE = "hud_scale";
    public static final String K_FONT = "font";
    public static final String K_ARMED_MARKER = "armed_marker";

    /** Text face: the suite font when its files are present, or always the game's own. */
    public enum FontFace {
        SUITE, VANILLA
    }

    /** Accent presets (0xRRGGBB). Status hues never move with the accent. */
    public enum Accent {
        CYAN(HoloTheme.DEFAULT_ACCENT_RGB), ICE(0xA8E9FF), GREEN(0x4DFFA0), AMBER(0xFFB547), MAGENTA(0xFF5CD0);

        private final int rgb;

        Accent(int rgb) {
            this.rgb = rgb;
        }

        public int rgb() {
            return rgb;
        }
    }

    private static final List<SettingSpec> SPECS = List.of(
            SettingSpec.choice(K_EFFECTS, "Effects", EffectLevel.FULL, "",
                    "Off: flat plates and plain 1 px lines, no glow. Subtle: glow and soft lines. Full: everything."),
            SettingSpec.choice(K_MOTION, "Motion", Motion.FULL, "",
                    "Off: every transition is instant. Reduced: fades only, no slides. Full: fades, slides and "
                            + "lock-on snaps."),
            SettingSpec.choice(K_ACCENT, "Accent colour", Accent.CYAN, "",
                    "Colour of the system lines and highlights. Status colours (danger, warning, acting) stay fixed."),
            SettingSpec.integer(K_HUD_OPACITY, "HUD opacity (%)", 100, 40, 100, "",
                    "Multiplies the opacity of everything the suite draws on the in-game HUD. The menu ignores it."),
            SettingSpec.integer(K_HUD_SCALE, "HUD scale (%)", 100, 75, 150, "",
                    "Size of the suite's in-game HUD on top of the game's GUI scale. The menu keeps its own scale."),
            SettingSpec.choice(K_FONT, "Font", FontFace.SUITE, "",
                    "Suite: a clean UI typeface with tabular numerals, once its font files are installed (vanilla "
                            + "until then, and whenever Force Unicode Font is on). Vanilla: always the game's own font."),
            SettingSpec.bool(K_ARMED_MARKER, "Armed marker", false, "",
                    "Shows four dim corner brackets while the autopilot is on and idle."));

    // ---- IModule ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.CORE;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        // always on: the appearance applies whether or not anything is drawn
    }

    @Override
    public void onTick() {
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        apply(profile);
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Appearance";
    }

    @Override
    public List<SettingSpec> settings() {
        return SPECS;
    }

    @Override
    public void onSettingChanged(String key) {
        apply(Services.modState().profile());
    }

    /** Reads every appearance setting from {@code p} into {@link HoloTheme}. Not a hot path. */
    public static void apply(ProfileConfig p) {
        HoloTheme.effects = parse(EffectLevel.class, p.getString(ID, K_EFFECTS, EffectLevel.FULL.name()),
                EffectLevel.FULL);
        HoloTheme.motion = parse(Motion.class, p.getString(ID, K_MOTION, Motion.FULL.name()), Motion.FULL);
        HoloTheme.setAccent(parse(Accent.class, p.getString(ID, K_ACCENT, Accent.CYAN.name()), Accent.CYAN).rgb());
        HoloTheme.hudOpacity = clamp(p.getInt(ID, K_HUD_OPACITY, 100), 40, 100) / 100f;
        HoloTheme.hudScale = clamp(p.getInt(ID, K_HUD_SCALE, 100), 75, 150) / 100f;
        HoloTheme.suiteFont = parse(FontFace.class, p.getString(ID, K_FONT, FontFace.SUITE.name()), FontFace.SUITE)
                == FontFace.SUITE;
        HoloTheme.armedMarker = p.getBool(ID, K_ARMED_MARKER, false);
    }

    /** Writes the current {@link HoloTheme} values and {@code accent} into {@code p} (profile copy, tests). */
    public static void store(ProfileConfig p, Accent accent) {
        p.set(ID, K_EFFECTS, HoloTheme.effects.name());
        p.set(ID, K_MOTION, HoloTheme.motion.name());
        p.set(ID, K_ACCENT, accent.name());
        p.set(ID, K_HUD_OPACITY, Math.round(HoloTheme.hudOpacity * 100f));
        p.set(ID, K_HUD_SCALE, Math.round(HoloTheme.hudScale * 100f));
        p.set(ID, K_FONT, (HoloTheme.suiteFont ? FontFace.SUITE : FontFace.VANILLA).name());
        p.set(ID, K_ARMED_MARKER, HoloTheme.armedMarker);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
