package com.utilitysuite.world;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.IModuleRegistry;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.world.blast.BlastOverlayModule;
import com.utilitysuite.world.camera.FreelookModule;
import com.utilitysuite.world.camera.ScoutCameraModule;
import com.utilitysuite.world.lighting.LightOverlayModule;
import com.utilitysuite.world.spawn.SpawnGeometryModule;
import com.utilitysuite.world.knowledge.KnowledgeLedger;
import com.utilitysuite.world.knowledge.WorldKnowledgeModule;
import com.utilitysuite.world.knowledge.WorldKnowledgeService;
import com.utilitysuite.world.minimap.MinimapModule;
import com.utilitysuite.world.oresim.OreSimModule;
import com.utilitysuite.world.overlays.WorldOverlaysModule;
import com.utilitysuite.world.overlays.WorldRadarModule;
import com.utilitysuite.world.waypoints.WaypointsModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.minecraft.resources.ResourceLocation;
import com.utilitysuite.UtilitySuiteMod;
import net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Wires the world modules (Sub-Agent D): World Knowledge, X-Ray Filters, Seed Workbench &amp; OreSim, World Radar,
 * Scout Camera and Waypoints. Registers {@code IWorldKnowledgeService} and {@code IWaypointService}, the modules, the
 * extra key mappings (zoom, add waypoint, take me back) and every event listener. Called once from
 * {@code UtilitySuiteMod} after the core.
 */
public final class WorldBootstrap {

    private static WorldRuntime runtime;

    private WorldBootstrap() {
    }

    public static void init(IEventBus modBus) {
        runtime = new WorldRuntime();
        ISuiteEventBus events = Services.events();

        KnowledgeLedger ledger = new KnowledgeLedger();
        WorldKnowledgeService knowledgeService = new WorldKnowledgeService(ledger, events::post, () -> {
            Minecraft mc = Minecraft.getInstance();
            return mc.level == null ? 0L : mc.level.getGameTime();
        });
        knowledgeService.setObservationSource(runtime.scanner().observed);
        Services.registerWorldKnowledge(knowledgeService);

        WorldKnowledgeModule knowledge = new WorldKnowledgeModule(knowledgeService);
        WorldOverlaysModule overlays = new WorldOverlaysModule(runtime, knowledgeService);
        OreSimModule oreSim = new OreSimModule(knowledgeService, runtime.scanner());
        knowledge.setPredictionSource(oreSim.predictions());
        WorldRadarModule radar = new WorldRadarModule(runtime);
        ScoutCameraModule camera = new ScoutCameraModule();
        WaypointsModule waypoints = new WaypointsModule();
        MinimapModule minimap = new MinimapModule();
        LightOverlayModule lightOverlay = new LightOverlayModule();
        BlastOverlayModule blastOverlay = new BlastOverlayModule();
        SpawnGeometryModule spawnGeometry = new SpawnGeometryModule(oreSim::profile);
        FreelookModule freelook = new FreelookModule();
        modBus.addListener(RegisterGuiLayersEvent.class, e -> e.registerBelow(VanillaGuiLayers.CHAT,
                ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "minimap"), minimap.hud()));
        Services.registerWaypoints(waypoints.store());
        waypoints.subscribe(events);

        IModuleRegistry registry = Services.modules();
        for (WorldModule m : new WorldModule[] {knowledge, overlays, oreSim, radar, camera, waypoints, minimap,
                lightOverlay, blastOverlay, spawnGeometry, freelook}) {
            registry.register(m);
            runtime.add(m);
        }

        // Keys: quick toggles from the core's key set, plus three of our own.
        KeybindManager.onPressed(KeybindManager.KeyId.TOGGLE_WORLD_OVERLAYS, overlays::toggleFromKey);
        KeybindManager.onPressed(KeybindManager.KeyId.TOGGLE_SCOUT_CAMERA, camera::toggleFreecam);
        // Created in the registration event (not during parallel mod construction: KeyMapping keeps a global map).
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            KeyMapping zoomKey = key("zoom", GLFW.GLFW_KEY_Z);
            KeyMapping addWaypointKey = key("add_waypoint", InputConstants.UNKNOWN.getValue());
            KeyMapping takeMeBackKey = key("take_me_back", InputConstants.UNKNOWN.getValue());
            e.register(zoomKey);
            e.register(addWaypointKey);
            e.register(takeMeBackKey);
            camera.setZoomKey(zoomKey);
            KeyMapping freelookKey = key("freelook", GLFW.GLFW_KEY_LEFT_ALT);
            e.register(freelookKey);
            freelook.setKey(freelookKey);
            waypoints.setKeys(addWaypointKey, takeMeBackKey);
        });

        // Mixin hooks.
        WorldHooks.setBlockChangeListener((pos, o, n) -> {
            runtime.onBlockChanged(pos, o, n);
            lightOverlay.onBlockChanged(pos, o, n);
        });
        WorldHooks.setTurnRedirect((yRot, xRot) -> camera.turn(yRot, xRot) || freelook.turn(yRot, xRot));

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(runtime::onTick);
        bus.addListener(runtime::onRender);
        bus.addListener((ChunkEvent.Load e) -> {
            runtime.onChunkLoad(e);
            if (e.getLevel().isClientSide()) {
                lightOverlay.onChunkLoaded(e.getChunk().getPos().x, e.getChunk().getPos().z);
            }
        });
        bus.addListener((ChunkEvent.Unload e) -> runtime.onChunkUnload(e));
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> runtime.onWorldUnload());
        bus.addListener((ClientChatReceivedEvent.System e) -> oreSim.onChat(e));
        bus.addListener((MovementInputUpdateEvent e) -> camera.onMovementInput(e));
        bus.addListener((InputEvent.InteractionKeyMappingTriggered e) -> camera.onInteraction(e));
        bus.addListener((InputEvent.MouseScrollingEvent e) -> camera.onScroll(e));
        bus.addListener((RenderHandEvent e) -> camera.onRenderHand(e));
        bus.addListener((ViewportEvent.ComputeFov e) -> camera.onComputeFov(e));
        bus.addListener((ViewportEvent.ComputeCameraAngles e) -> freelook.onCameraAngles(e));
        bus.addListener((ViewportEvent.RenderFog e) -> camera.onFog(e));
        bus.addListener((CalculatePlayerTurnEvent e) -> camera.onTurnSensitivity(e));
        bus.addListener((RenderBlockScreenEffectEvent e) -> camera.onScreenEffect(e));
        bus.addListener((RenderGuiLayerEvent.Pre e) -> camera.onGuiLayer(e));
    }

    private static KeyMapping key(String name, int defaultKey) {
        return new KeyMapping("key.utilitysuite." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                defaultKey, KeybindManager.CATEGORY);
    }

    /** For diagnostics and tests of the wiring. */
    public static WorldRuntime runtime() {
        return runtime;
    }
}
