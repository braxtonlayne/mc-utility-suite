package com.utilitysuite.world.atmosphere;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldHooks;
import com.utilitysuite.world.WorldModule;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.List;

/**
 * Atmosphere studio: client-side looks for the sky fog and dark areas. Presets:
 * <ul>
 *   <li>Warm Twilight: dark areas are lifted part of the way (a fraction of vanilla's own night-vision brightening,
 *   through the existing lightmap hook) and the fog gets a warm tint, instead of flat fullbright.</li>
 *   <li>Clear Water: the underwater fog is pushed far out; the fog colour stays vanilla's, so depth still tints.</li>
 *   <li>Sky Ramp: the fog/horizon colour follows a custom dawn/day/dusk/night ramp by time of day.</li>
 *   <li>Biome: the fog colour eases toward a per-biome tint as you walk between biomes.</li>
 * </ul>
 * The sky presets also recolour the sky itself and, more gently, the clouds: a target colour and weight are published
 * once per tick through {@link WorldHooks#setSkyTint} and blended per frame by {@code ClientLevelSkyColorMixin} (no
 * event exposes the sky or cloud colour). Fog uses NeoForge's {@link ViewportEvent.ComputeFogColor} and
 * {@link ViewportEvent.RenderFog}; dark areas use the existing light texture hook. Drawing only: nothing is sent.
 * Clean-Screen restores vanilla. Off by default.
 */
public final class AtmosphereModule extends WorldModule {

    public static final String ID = "atmosphere";

    public enum Preset { WARM_TWILIGHT, CLEAR_WATER, SKY_RAMP, BIOME }

    private Preset preset = Preset.WARM_TWILIGHT;
    private float strength;
    private int waterDistance;
    private int dawn;
    private int day;
    private int dusk;
    private int night;
    private AtmosphereMath.ColorRamp ramp;
    private final AtmosphereMath.BlendedTint biome = new AtmosphereMath.BlendedTint();
    private Holder<Biome> lastBiome;
    private int biomeTarget = AtmosphereMath.NO_TINT;
    /** Sky brightness 0..1 of the current time of day (updated each tick). */
    private float daylight = 1f;

    public AtmosphereModule() {
        super(ID, "Atmosphere Studio", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice("preset", "Preset", Preset.WARM_TWILIGHT, "Look",
                        "Warm Twilight lifts dark areas with a warm tint; Clear Water pushes water fog away; Sky Ramp "
                                + "colours the horizon by time of day; Biome tints by the biome you stand in."),
                SettingSpec.decimal("strength", "Strength", 0.6, 0.0, 1.0, "Look", "How strong the effect is."),
                SettingSpec.integer("water_distance", "Clear Water distance", 96, 16, 256, "Clear Water",
                        "How far you see under water (blocks)."),
                SettingSpec.text("dawn", "Dawn colour", "#F0A070", "Sky Ramp", "Hex colour at sunrise."),
                SettingSpec.text("day", "Day colour", "#A8CCF8", "Sky Ramp", "Hex colour at noon."),
                SettingSpec.text("dusk", "Dusk colour", "#E07850", "Sky Ramp", "Hex colour at sunset."),
                SettingSpec.text("night", "Night colour", "#141C38", "Sky Ramp", "Hex colour at midnight."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        preset = choice(p, "preset", Preset.class);
        strength = (float) decimal(p, "strength");
        waterDistance = integer(p, "water_distance");
        dawn = AtmosphereMath.parseHex(text(p, "dawn"), 0xF0A070);
        day = AtmosphereMath.parseHex(text(p, "day"), 0xA8CCF8);
        dusk = AtmosphereMath.parseHex(text(p, "dusk"), 0xE07850);
        night = AtmosphereMath.parseHex(text(p, "night"), 0x141C38);
        ramp = AtmosphereMath.ColorRamp.fourKey(dawn, day, dusk, night);
        publish();
    }

    @Override
    protected void onEnable() {
        publish();
    }

    @Override
    protected void onDisable() {
        WorldHooks.setLightLift(0f);
        WorldHooks.clearSkyTint();
        biome.reset();
        lastBiome = null;
    }

    @Override
    public void onWorldUnload() {
        WorldHooks.clearSkyTint();
        biome.reset();
        lastBiome = null;
    }

    private void publish() {
        boolean lift = isEnabled() && preset == Preset.WARM_TWILIGHT && !cleanScreen();
        WorldHooks.setLightLift(lift ? AtmosphereMath.lightLift(strength) : 0f);
    }

    @Override
    public void onTick() {
        publish(); // follows Clean-Screen; a volatile write, no allocation
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            WorldHooks.clearSkyTint();
            return;
        }
        if (preset == Preset.BIOME) {
            Holder<Biome> h = mc.level.getBiome(mc.player.blockPosition());
            if (h != lastBiome) { // only on a biome change: the key lookup allocates
                lastBiome = h;
                biomeTarget = h.unwrapKey().map(k -> AtmosphereMath.biomeTint(k.location().getPath()))
                        .orElse(AtmosphereMath.NO_TINT);
            }
            biome.step(biomeTarget, 0.05f);
        }
        publishSky(mc);
    }

    /**
     * Sky and cloud targets for the mixin, once per tick (colours change slowly; the mixin blends every frame).
     * Sky Ramp paints the absolute ramp colour; Biome tints by the biome colour scaled by daylight; Warm Twilight
     * warms the sky slightly; Clear Water leaves it alone. Rain and thunder damp the weight. Allocation free.
     */
    private void publishSky(Minecraft mc) {
        if (cleanScreen()) {
            WorldHooks.clearSkyTint();
            return;
        }
        daylight = AtmosphereMath.daylight(mc.level.getTimeOfDay(1f));
        int target;
        float weight;
        switch (preset) {
            case SKY_RAMP -> {
                target = ramp == null ? 0 : ramp.sample(mc.level.getDayTime());
                weight = ramp == null ? 0f : strength;
            }
            case BIOME -> {
                target = AtmosphereMath.scaleRgb(AtmosphereMath.rgb(Math.round(biome.r), Math.round(biome.g),
                        Math.round(biome.b)), Math.max(0.08f, daylight));
                weight = 0.6f * strength * biome.weight;
            }
            case WARM_TWILIGHT -> {
                target = AtmosphereMath.scaleRgb(AtmosphereMath.WARM, Math.max(0.1f, daylight));
                weight = 0.2f * strength;
            }
            default -> {
                target = 0;
                weight = 0f;
            }
        }
        weight = AtmosphereMath.weatherDamp(weight, mc.level.getRainLevel(1f), mc.level.getThunderLevel(1f));
        WorldHooks.setSkyTint(target, weight);
        WorldHooks.setCloudTint(AtmosphereMath.cloudFor(target, daylight), weight * 0.6f);
    }

    /** Fog/horizon colour for the sky presets. Per frame, no allocation. */
    public void onFogColor(ViewportEvent.ComputeFogColor e) {
        if (!isEnabled() || cleanScreen() || e.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        switch (preset) {
            case WARM_TWILIGHT -> tint(e, AtmosphereMath.r(AtmosphereMath.WARM), AtmosphereMath.g(AtmosphereMath.WARM),
                    AtmosphereMath.b(AtmosphereMath.WARM), 0.3f * strength);
            case SKY_RAMP -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null && ramp != null) {
                    int c = ramp.sample(mc.level.getDayTime());
                    tint(e, AtmosphereMath.r(c), AtmosphereMath.g(c), AtmosphereMath.b(c), strength);
                }
            }
            case BIOME -> {
                if (biome.weight > 0.001f) {
                    float dl = Math.max(0.08f, daylight); // the horizon darkens at night like the tinted sky
                    tint(e, biome.r * dl, biome.g * dl, biome.b * dl, 0.6f * strength * biome.weight);
                }
            }
            default -> {
            }
        }
    }

    private static void tint(ViewportEvent.ComputeFogColor e, float r, float g, float b, float t) {
        e.setRed(AtmosphereMath.blendChannel(e.getRed(), r, t));
        e.setGreen(AtmosphereMath.blendChannel(e.getGreen(), g, t));
        e.setBlue(AtmosphereMath.blendChannel(e.getBlue(), b, t));
    }

    /** Clear Water: push the underwater fog out. Vanilla's water fog colour (and its depth darkening) stays. */
    public void onFog(ViewportEvent.RenderFog e) {
        if (!isEnabled() || preset != Preset.CLEAR_WATER || cleanScreen() || e.getType() != FogType.WATER) {
            return;
        }
        float far = Math.max(e.getFarPlaneDistance(), waterDistance * (0.25f + 0.75f * strength));
        e.setNearPlaneDistance(Math.min(e.getNearPlaneDistance(), far * 0.1f));
        e.setFarPlaneDistance(far);
        e.setCanceled(true);
    }
}
