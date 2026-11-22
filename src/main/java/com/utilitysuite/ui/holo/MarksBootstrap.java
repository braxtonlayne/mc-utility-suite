package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.IWorldMarks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wires the world-mark reticles and the authority indicator: the {@link ScreenProjector} listens to the level render,
 * and the {@link ReticleLayer} and {@link AuthorityIndicator} are registered with {@code Services.visuals().hud()} on
 * the first client tick (the Shell registers its host late in start-up, so registering in {@link #init} would hit the
 * no-op host). On logout every mark is cleared (claims stay: modules keep the slots they own).
 * Called once, after {@code UiBootstrap.init(modBus)}.
 */
public final class MarksBootstrap {

    private static final ScreenProjector PROJECTOR = new ScreenProjector();
    private static ReticleLayer reticles;
    private static AuthorityIndicator authority;
    private static boolean registered;

    private MarksBootstrap() {
    }

    public static void init(IEventBus modBus) {
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(RenderLevelStageEvent.class, PROJECTOR::onRenderLevel);
        bus.addListener(ClientTickEvent.Post.class, MarksBootstrap::onClientTick);
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> clearMarks());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (registered) {
            return;
        }
        IVisuals v = Services.visualsOrNull();
        if (v == null) {
            return; // the Shell has not registered its drawing services yet
        }
        IWorldMarks marks = v.marks();
        PROJECTOR.bind(marks);
        reticles = new ReticleLayer(PROJECTOR, marks);
        authority = new AuthorityIndicator(v.authority());
        v.hud().register(reticles);
        v.hud().register(authority);
        registered = true;
    }

    private static void clearMarks() {
        IWorldMarks marks = Services.visuals().marks();
        for (int s = 0; s < IWorldMarks.SLOTS; s++) {
            marks.clear(s);
        }
        PROJECTOR.clearStates();
    }

    public static ScreenProjector projector() {
        return PROJECTOR;
    }
}
