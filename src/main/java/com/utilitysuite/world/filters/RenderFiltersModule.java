package com.utilitysuite.world.filters;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldModule;

import java.util.List;

/**
 * Render filters: hide the hurt-camera tilt, rain/snow, the totem (item activation) animation and explosion
 * particles, and draw a fixed time of day or weather. Everything is client-side drawing only: nothing here changes
 * what the client sends, and the real time/weather still arrive and are kept (switching off shows them again at once).
 * All settings default off. The hooks are small commented mixins in {@code mixin/world} reading
 * {@link RenderFilterHooks}; Clean-Screen does not affect these (they remove drawing, they do not add any).
 */
public final class RenderFiltersModule extends WorldModule {

    public static final String ID = "render_filters";

    public enum WeatherOverride { REAL, CLEAR, RAIN, THUNDER }

    private boolean hurtTilt;
    private boolean weather;
    private boolean totem;
    private boolean explosions;
    private boolean fixTime;
    private int time;
    private WeatherOverride weatherOverride = WeatherOverride.REAL;

    public RenderFiltersModule() {
        super(ID, "Render Filters", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("hurt_tilt", "Hide hurt tilt", false, "Hide", "No camera shake when you take damage."),
                SettingSpec.bool("weather", "Hide rain and snow", false, "Hide",
                        "No falling rain/snow and no splash particles (the sky still darkens)."),
                SettingSpec.bool("totem", "Hide totem animation", false, "Hide",
                        "No full-screen item pop when a totem (or similar) activates."),
                SettingSpec.bool("explosions", "Hide explosion particles", false, "Hide",
                        "No explosion clouds (the blast still happens)."),
                SettingSpec.bool("fix_time", "Fixed time of day", false, "Client-side sky",
                        "Draw the sky at the time below. Only changes what you see."),
                SettingSpec.integer("time", "Time (ticks)", 6000, 0, 23999, "Client-side sky",
                        "0 sunrise, 6000 noon, 13000 night, 18000 midnight."),
                SettingSpec.choice("weather_override", "Weather shown", WeatherOverride.REAL, "Client-side sky",
                        "Draw this weather instead of the real one. Only changes what you see."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        hurtTilt = bool(p, "hurt_tilt");
        weather = bool(p, "weather");
        totem = bool(p, "totem");
        explosions = bool(p, "explosions");
        fixTime = bool(p, "fix_time");
        time = integer(p, "time");
        weatherOverride = choice(p, "weather_override", WeatherOverride.class);
        if (isEnabled()) {
            publish();
        }
    }

    @Override
    protected void onEnable() {
        publish();
    }

    @Override
    protected void onDisable() {
        RenderFilterHooks.resetAll();
    }

    @Override
    public void onTick() {
        // Event-free module: everything is published on change.
    }

    private void publish() {
        RenderFilterHooks.hideHurtTilt = hurtTilt;
        RenderFilterHooks.hideWeather = weather;
        RenderFilterHooks.hideItemActivation = totem;
        RenderFilterHooks.hideExplosionParticles = explosions;
        RenderFilterHooks.dayTime = fixTime ? time : RenderFilterHooks.NO_TIME;
        RenderFilterHooks.rainLevel = rainFor(weatherOverride);
        RenderFilterHooks.thunderLevel = thunderFor(weatherOverride);
    }

    static float rainFor(WeatherOverride w) {
        return switch (w) {
            case REAL -> Float.NaN;
            case CLEAR -> 0f;
            case RAIN, THUNDER -> 1f;
        };
    }

    static float thunderFor(WeatherOverride w) {
        return switch (w) {
            case REAL -> Float.NaN;
            case CLEAR, RAIN -> 0f;
            case THUNDER -> 1f;
        };
    }
}
