package com.utilitysuite;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry point. Client-only mod: everything is wired from here onto the mod bus and {@code NeoForge.EVENT_BUS}.
 * Core infrastructure registers first, then feature modules (each package exposes a static {@code bootstrap(...)}).
 */
@Mod(value = UtilitySuiteMod.MOD_ID, dist = Dist.CLIENT)
public final class UtilitySuiteMod {

    public static final String MOD_ID = "utilitysuite";
    public static final Logger LOGGER = LogUtils.getLogger();

    public UtilitySuiteMod(IEventBus modBus, ModContainer container) {
        LOGGER.info("Utility Suite loading");
        // Core and module bootstraps are wired here by later milestones.
    }
}
