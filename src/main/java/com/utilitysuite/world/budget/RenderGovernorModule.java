package com.utilitysuite.world.budget;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.RenderBudget;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldModule;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.List;

/**
 * FPS / render-budget governor. Measures frame times every frame into a preallocated ring, and once a second compares
 * the 1% low over the last ~2 s with the target. Under target it steps the shared {@link RenderBudget} tier down
 * (1: overlay density caps scale down; 2: also keep half the particles; 3: also a quarter, and distant armor stands are
 * not drawn); with hysteresis it steps back up. Opt-in (all off by default): from a chosen degraded tier, item frames
 * (and glow item frames), item displays and armor stands beyond a distance are not drawn either; frames and displays
 * through {@link EntityCullHooks} (an {@code EntityRenderDispatcher.shouldRender} mixin, since they are not living
 * entities), armor stands through {@code RenderLivingEvent}. Off by default; turning it off restores full detail at
 * once.
 */
public final class RenderGovernorModule extends WorldModule {

    public static final String ID = "render_governor";
    static final long WINDOW_NANOS = 2_000_000_000L;
    static final int MIN_FRAMES = 20;

    private static final String[] PHASES = {null, "TIER_1", "TIER_2", "TIER_3"};

    private final FrameTimeRing ring = new FrameTimeRing(1024);
    private final BudgetGovernor governor = new BudgetGovernor();
    private int targetFps;
    private int hysteresisPct;
    private int maxTier;
    private long lastFrame;
    private int ticks;
    private double lastLow = Double.NaN;
    private boolean cullItemFrames;
    private boolean cullItemDisplays;
    private boolean cullArmorStands;
    private double cullDistance;
    private int cullFromTier;
    private int cullMask;

    public RenderGovernorModule() {
        super(ID, "FPS Governor", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("target_fps", "Target FPS", 60, 20, 240, "Budget",
                        "When the 1% low drops under this, optional detail is reduced step by step."),
                SettingSpec.integer("hysteresis_pct", "Step-up margin (%)", 15, 5, 50, "Budget",
                        "The 1% low must be this much above the target for a few seconds before detail comes back."),
                SettingSpec.integer("max_tier", "Deepest step", 3, 1, 3, "Budget",
                        "1 thins overlays; 2 also drops half the particles; 3 drops 3/4 and hides far armor stands."),
                SettingSpec.bool("cull_item_frames", "Hide far item frames", false, "Entity culling",
                        "While detail is reduced, item frames and glow item frames past the cull distance are not "
                                + "drawn (they stay in the world)."),
                SettingSpec.bool("cull_item_displays", "Hide far item displays", false, "Entity culling",
                        "While detail is reduced, item display entities past the cull distance are not drawn."),
                SettingSpec.bool("cull_armor_stands", "Hide far armor stands early", false, "Entity culling",
                        "Also hide armor stands past the cull distance from the chosen step (step 3 always hides "
                                + "them past 24 blocks)."),
                SettingSpec.decimal("cull_distance", "Cull distance", 32.0, 8.0, 128.0, "Entity culling",
                        "Blocks from the camera beyond which the chosen entities are hidden."),
                SettingSpec.integer("cull_from_tier", "Cull from step", 2, 1, 3, "Entity culling",
                        "The first reduced-detail step at which the entity culling above starts."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        targetFps = integer(p, "target_fps");
        hysteresisPct = integer(p, "hysteresis_pct");
        maxTier = integer(p, "max_tier");
        governor.configure(maxTier, hysteresisPct / 100.0, 2, 4);
        cullItemFrames = bool(p, "cull_item_frames");
        cullItemDisplays = bool(p, "cull_item_displays");
        cullArmorStands = bool(p, "cull_armor_stands");
        cullDistance = decimal(p, "cull_distance");
        cullFromTier = integer(p, "cull_from_tier");
        cullMask = (cullItemFrames ? EntityCullPolicy.ITEM_FRAME : 0)
                | (cullItemDisplays ? EntityCullPolicy.ITEM_DISPLAY : 0)
                | (cullArmorStands ? EntityCullPolicy.ARMOR_STAND : 0);
        if (isEnabled()) {
            RenderBudget.setTier(governor.tier());
            EntityCullHooks.configure(cullMask, cullFromTier, cullDistance);
        }
    }

    @Override
    protected void onEnable() {
        ring.clear();
        governor.reset();
        lastFrame = 0L;
        RenderBudget.reset();
        EntityCullHooks.configure(cullMask, cullFromTier, cullDistance);
    }

    @Override
    protected void onDisable() {
        governor.reset();
        RenderBudget.reset();
        EntityCullHooks.clear();
    }

    @Override
    public void onWorldUnload() {
        ring.clear();
        governor.reset();
        lastFrame = 0L;
        RenderBudget.reset();
    }

    @Override
    public String phase() {
        return isEnabled() ? PHASES[governor.tier()] : null;
    }

    /** Last 1% low reading (NaN before the first one). */
    public double lastOnePercentLow() {
        return lastLow;
    }

    /** Every frame: one subtraction and two array writes. */
    public void onFrame(RenderFrameEvent.Pre e) {
        if (!isEnabled()) {
            return;
        }
        long now = System.nanoTime();
        long last = lastFrame;
        lastFrame = now;
        Minecraft mc = Minecraft.getInstance();
        if (last == 0L || mc.isPaused() || mc.player == null) {
            return;
        }
        ring.add(now, now - last);
    }

    @Override
    public void onTick() {
        if (++ticks < 20) {
            return;
        }
        ticks = 0;
        lastLow = ring.onePercentLowFps(System.nanoTime(), WINDOW_NANOS, MIN_FRAMES);
        RenderBudget.setTier(governor.evaluate(lastLow, targetFps));
    }

    /**
     * Distant armor stands are not drawn at tier 3 (fixed rule) or, opt-in, from the chosen step past the cull
     * distance. They stay in the world; only their rendering is skipped. Allocation free.
     */
    public void onRenderLiving(RenderLivingEvent.Pre<?, ?, ?> e) {
        int tier = RenderBudget.tier();
        // 26.3 renders from extracted state: identify the armor stand by its entity type
        net.minecraft.client.renderer.entity.state.LivingEntityRenderState st = e.getRenderState();
        if (tier == 0 || st.entityType != net.minecraft.world.entity.EntityTypes.ARMOR_STAND) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double dx = st.x - mc.player.getX();
        double dy = st.y - mc.player.getY();
        double dz = st.z - mc.player.getZ();
        if (EntityCullPolicy.shouldCullArmorStand(cullMask, tier, cullFromTier,
                dx * dx + dy * dy + dz * dz, cullDistance * cullDistance)) {
            e.setCanceled(true);
        }
    }
}
