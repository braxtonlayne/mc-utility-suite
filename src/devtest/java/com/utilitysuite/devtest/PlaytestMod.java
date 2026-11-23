package com.utilitysuite.devtest;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Dev-only entry point of the scripted playtest harness. Loaded only by the {@code playtest} run
 * ({@code ./gradlew runPlaytest}) and inert unless {@code -Dutilitysuite.playtest=true} is set.
 */
@Mod(value = PlaytestMod.MOD_ID, dist = Dist.CLIENT)
public final class PlaytestMod {

    public static final String MOD_ID = "utilitysuite_devtest";
    public static final Logger LOG = LogUtils.getLogger();

    public PlaytestMod(IEventBus modBus) {
        if (Boolean.getBoolean("utilitysuite.arena")) {
            LOG.info("Arena client ACTIVE");
            new ArenaClient().register(NeoForge.EVENT_BUS);
            return;
        }
        if (Boolean.getBoolean("utilitysuite.course")) {
            LOG.info("Course client ACTIVE");
            new CourseLive().register(NeoForge.EVENT_BUS);
            return;
        }
        if (!Boolean.getBoolean("utilitysuite.playtest")) {
            LOG.info("Playtest harness present but inactive (-Dutilitysuite.playtest not set)");
            return;
        }
        LOG.info("Playtest harness ACTIVE");
        Harness harness = new Harness();
        harness.register(NeoForge.EVENT_BUS);
    }
}
