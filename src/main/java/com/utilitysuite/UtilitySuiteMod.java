package com.utilitysuite;

import com.mojang.logging.LogUtils;
import com.utilitysuite.combat.CombatBootstrap;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.jobs.JobsBootstrap;
import com.utilitysuite.jobs.schematic.SchematicBootstrap;
import com.utilitysuite.tools.ToolsBootstrap;
import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.ui.UiBootstrap;
import com.utilitysuite.world.WorldBootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.slf4j.Logger;

/**
 * Entry point. Client-only mod: everything is wired from here onto the mod bus and {@code NeoForge.EVENT_BUS}.
 * Core infrastructure registers first (it fills {@code Services}), then the feature areas. Order matters only for
 * registration; modules look each other up lazily through {@code Services}.
 */
@Mod(value = UtilitySuiteMod.MOD_ID, dist = Dist.CLIENT)
public final class UtilitySuiteMod {

    public static final String MOD_ID = "utilitysuite";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Dev-only: set by the {@code runClient} configuration to force-apply every mixin at startup and log failures. */
    private static final String MIXIN_AUDIT_PROPERTY = "utilitysuite.mixinAudit";

    public UtilitySuiteMod(IEventBus modBus, ModContainer container) {
        LOGGER.info("Utility Suite loading");
        CoreBootstrap.init(modBus);       // arbiter, input smoother, clicker, config, mod state, modules, events, keys

        JobsBootstrap.init(modBus);       // goal engine, planner, Baritone adapter, storage memory, survival autopilot
        SchematicBootstrap.init(modBus);  // schematic toolkit (+ build handler for the goal engine)
        WorldBootstrap.init(modBus);      // world knowledge, overlays, OreSim, radar, scout camera, waypoints
        TravelBootstrap.init(modBus);     // terrain assist, travel autopilot, bridge assist
        CombatBootstrap.init(modBus);     // combat assistant, ally roster, fight coach
        ToolsBootstrap.init(modBus);      // smart inventory, packet lab, local rules
        UiBootstrap.init(modBus);         // menu screen (Right Shift), HUD status line

        if (Boolean.getBoolean(MIXIN_AUDIT_PROPERTY)) {
            modBus.addListener(FMLLoadCompleteEvent.class, e -> e.enqueueWork(UtilitySuiteMod::auditMixins));
        }
    }

    private static void auditMixins() {
        try {
            org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
            LOGGER.info("Utility Suite mixin audit finished (failures, if any, are logged above by Mixin)");
        } catch (Throwable t) {
            LOGGER.error("Utility Suite mixin audit failed", t);
        }
    }
}
