package com.utilitysuite.world.budget;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.RenderBudget;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.List;

/**
 * FPS / render-budget governor. Measures frame times every frame into a preallocated ring, and once a second compares
 * the 1% low over the last ~2 s with the target. Under target it steps the shared {@link RenderBudget} tier down
 * (1: overlay density caps scale down; 2: also keep half the particles; 3: also a quarter, and distant armor stands are
 * not drawn); with hysteresis it steps back up. Off by default; turning it off restores full detail at once.
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
                        "1 thins overlays; 2 also drops half the particles; 3 drops 3/4 and hides far armor stands."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        targetFps = integer(p, "target_fps");
        hysteresisPct = integer(p, "hysteresis_pct");
        maxTier = integer(p, "max_tier");
        governor.configure(maxTier, hysteresisPct / 100.0, 2, 4);
        if (isEnabled()) {
            RenderBudget.setTier(governor.tier());
        }
    }

    @Override
    protected void onEnable() {
        ring.clear();
        governor.reset();
        lastFrame = 0L;
        RenderBudget.reset();
    }

    @Override
    protected void onDisable() {
        governor.reset();
        RenderBudget.reset();
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

    /** Tier 3: distant armor stands are not drawn (they stay in the world; only their rendering is skipped). */
    public void onRenderLiving(RenderLivingEvent.Pre<?, ?> e) {
        double cull = RenderBudget.armorStandCullDistanceSq();
        if (cull <= 0.0 || !(e.getEntity() instanceof ArmorStand stand)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && stand.distanceToSqr(mc.player) > cull) {
            e.setCanceled(true);
        }
    }
}
