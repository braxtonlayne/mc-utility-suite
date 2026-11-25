package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.IWorldMarks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Wires the world-mark reticles, the authority indicator, the world drawing kit and the suite font: the
 * {@link ScreenProjector} listens to the level render, and the {@link ReticleLayer} and {@link AuthorityIndicator} are registered with {@code Services.visuals().hud()} on
 * the first client tick (the Shell registers its host late in start-up, so registering in {@link #init} would hit the
 * no-op host). The {@link HoloWorldKit.Pass} brackets every other listener of its level-render stage (highest and
 * lowest priority) so adopters draw through {@code Services.visuals().world()} into one batch; {@link SuiteFonts}
 * registers its pack finder and reload check on the mod bus. On logout every mark is cleared (claims stay: modules
 * keep the slots they own).
 * Called once, after {@code UiBootstrap.init(modBus)}.
 */
public final class MarksBootstrap {

    private static final ScreenProjector PROJECTOR = new ScreenProjector();
    private static final HoloCardModule CARDS = new HoloCardModule(PROJECTOR);
    private static ReticleLayer reticles;
    private static AuthorityIndicator authority;
    private static boolean registered;

    private MarksBootstrap() {
    }

    public static void init(IEventBus modBus) {
        SuiteFonts.Client.init(modBus); // suite font: pack finder + reload check (switches on by file presence)
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(RenderLevelStageEvent.class, PROJECTOR::onRenderLevel);
        // the world kit pass: opened before every other listener of the stage, flushed after all of them
        bus.addListener(EventPriority.HIGHEST, RenderLevelStageEvent.class, HoloWorldKit.Pass::onBegin);
        bus.addListener(EventPriority.LOWEST, RenderLevelStageEvent.class, HoloWorldKit.Pass::onEnd);
        bus.addListener(ClientTickEvent.Post.class, MarksBootstrap::onClientTick);
        // V3 holo-cards (off by default): learns from opened menus, anchors through the projector
        Services.modules().register(CARDS);
        bus.addListener(PlayerInteractEvent.EntityInteract.class, CARDS::onEntityInteract);
        bus.addListener(PlayerInteractEvent.RightClickBlock.class, CARDS::onRightClickBlock);
        bus.addListener(ScreenEvent.Closing.class, CARDS::onScreenClosing);
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
        v.hud().register(new HoloCardLayer(CARDS.book(), PROJECTOR));
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
