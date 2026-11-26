package com.utilitysuite.world;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.IModuleRegistry;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.world.atmosphere.AtmosphereModule;
import com.utilitysuite.world.blast.BlastOverlayModule;
import com.utilitysuite.world.budget.RenderGovernorModule;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import com.utilitysuite.world.replay.PipCameraModule;
import com.utilitysuite.world.replay.ReplayTheatreModule;
import com.utilitysuite.world.camera.FreelookModule;
import com.utilitysuite.world.camera.ScoutCameraModule;
import com.utilitysuite.world.filters.RenderFiltersModule;
import com.utilitysuite.world.lighting.LightOverlayModule;
import com.utilitysuite.world.spawn.SpawnGeometryModule;
import com.utilitysuite.world.stealth.StealthVisionModule;
import com.utilitysuite.world.fortress.FortressSpawnModule;
import com.utilitysuite.world.stronghold.StrongholdFinderModule;
import com.utilitysuite.world.trial.TrialChamberModule;
import com.utilitysuite.world.knowledge.KnowledgeLedger;
import com.utilitysuite.world.knowledge.WorldKnowledgeModule;
import com.utilitysuite.world.knowledge.WorldKnowledgeService;
import com.utilitysuite.world.atlas.WorldAtlasModule;
import com.utilitysuite.world.minimap.MinimapModule;
import com.utilitysuite.world.oresim.OreSimModule;
import com.utilitysuite.world.overlays.WorldOverlaysModule;
import com.utilitysuite.world.overlays.WorldRadarModule;
import com.utilitysuite.world.seed.SeedDiffModule;
import com.utilitysuite.world.seed.SeedScannerModule;
import com.utilitysuite.world.seed.SeedLocatorModule;
import com.utilitysuite.world.sonar.CavityTomographyModule;
import com.utilitysuite.world.sonar.SonarPulseModule;
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
import net.minecraft.resources.Identifier;
import com.utilitysuite.UtilitySuiteMod;
import net.neoforged.neoforge.client.event.ExtractBlockScreenEffectEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

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
        SeedScannerModule seedScanner = new SeedScannerModule(oreSim::profile, oreSim::onScannedSeed);
        SeedDiffModule seedDiff = new SeedDiffModule(oreSim::profile);
        WorldRadarModule radar = new WorldRadarModule(runtime);
        ScoutCameraModule camera = new ScoutCameraModule();
        WaypointsModule waypoints = new WaypointsModule();
        MinimapModule minimap = new MinimapModule();
        LightOverlayModule lightOverlay = new LightOverlayModule();
        BlastOverlayModule blastOverlay = new BlastOverlayModule();
        com.utilitysuite.world.blast.CrystalExposureModule crystalExposure =
                new com.utilitysuite.world.blast.CrystalExposureModule();
        com.utilitysuite.world.overlays.ProjectileRibbonModule projectileRibbon =
                new com.utilitysuite.world.overlays.ProjectileRibbonModule();
        SpawnGeometryModule spawnGeometry = new SpawnGeometryModule(oreSim::profile);
        SeedLocatorModule seedLocator = new SeedLocatorModule(oreSim::profile);
        FreelookModule freelook = new FreelookModule();
        RenderFiltersModule renderFilters = new RenderFiltersModule();
        StrongholdFinderModule strongholdFinder = new StrongholdFinderModule();
        TrialChamberModule trialChambers = new TrialChamberModule(runtime);
        SonarPulseModule sonar = new SonarPulseModule(oreSim.predictions());
        CavityTomographyModule cavities = new CavityTomographyModule();
        WorldAtlasModule atlas = new WorldAtlasModule();
        com.utilitysuite.world.redstone.RedstoneTracerModule redstoneTracer =
                new com.utilitysuite.world.redstone.RedstoneTracerModule();
        com.utilitysuite.world.changes.ChangeTrackerModule changeTracker =
                new com.utilitysuite.world.changes.ChangeTrackerModule();
        StealthVisionModule stealthVision = new StealthVisionModule();
        FortressSpawnModule fortressSpawns = new FortressSpawnModule(oreSim::profile);
        AtmosphereModule atmosphere = new AtmosphereModule();
        RenderGovernorModule governor = new RenderGovernorModule();
        ReplayTheatreModule replay = new ReplayTheatreModule();
        PipCameraModule pip = new PipCameraModule();
        modBus.addListener(RegisterRenderPipelinesEvent.class, com.utilitysuite.world.render.WorldRenderKit::registerPipelines);
        modBus.addListener(RegisterGuiLayersEvent.class, e -> {
            e.registerBelow(VanillaGuiLayers.CHAT,
                    Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "pip_camera"), pip);
            e.registerBelow(VanillaGuiLayers.CHAT,
                    Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "replay_splits"), replay);
        });
        modBus.addListener(RegisterGuiLayersEvent.class, e -> e.registerBelow(VanillaGuiLayers.CHAT,
                Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "minimap"), minimap.hud()));
        modBus.addListener(RegisterGuiLayersEvent.class, e -> e.registerBelow(VanillaGuiLayers.CHAT,
                Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "acoustic_edge_cues"),
                new com.utilitysuite.world.overlays.AcousticEdgeHud(radar)));
        Services.registerWaypoints(waypoints.store());
        waypoints.subscribe(events);

        IModuleRegistry registry = Services.modules();
        for (WorldModule m : new WorldModule[] {knowledge, overlays, oreSim, seedScanner, seedDiff, radar, camera,
                waypoints,
                minimap, lightOverlay, blastOverlay, crystalExposure, projectileRibbon, spawnGeometry, seedLocator,
                freelook, renderFilters, strongholdFinder, trialChambers, sonar, cavities, atlas, redstoneTracer,
                changeTracker, stealthVision, fortressSpawns, atmosphere, governor, replay, pip}) {
            registry.register(m);
            runtime.add(m);
        }

        // Keys: quick toggles from the core's key set, plus three of our own.
        KeybindManager.onPressed(KeybindManager.KeyId.TOGGLE_WORLD_OVERLAYS, overlays::toggleFromKey);
        KeybindManager.onPressed(KeybindManager.KeyId.TOGGLE_SCOUT_CAMERA, camera::toggleFreecam);
        KeybindManager.onPressed(KeybindManager.KeyId.OPEN_WORLD_ATLAS, atlas::openScreen);
        com.utilitysuite.core.api.ISuiteActions actions = Services.actionsOrNull();
        if (actions != null) {
            actions.register(new com.utilitysuite.core.api.SuiteAction("drop_waypoint", "Drop Waypoint",
                    "add waypoint mark here", waypoints::requestAddHere));
            actions.register(new com.utilitysuite.core.api.SuiteAction("take_me_back", "Take Me Back",
                    "breadcrumbs return trail", waypoints::requestTakeMeBack));
            actions.register(new com.utilitysuite.core.api.SuiteAction("open_world_atlas", "Open World Atlas",
                    "map atlas 3d", atlas::openScreen));
            actions.register(new com.utilitysuite.core.api.SuiteAction("sonar_pulse", "Sonar Pulse",
                    "lidar scan pulse caves spawners", sonar::pulse));
            actions.register(new com.utilitysuite.core.api.SuiteAction("clear_change_snapshot_chunk",
                    "Clear change snapshot (this chunk)", "change tracker forget reset chunk",
                    () -> notice(changeTracker.clearChunkUnderPlayer())));
            actions.register(new com.utilitysuite.core.api.SuiteAction("clear_change_snapshots_dimension",
                    "Clear change snapshots (this dimension)", "change tracker forget reset dimension",
                    () -> notice(changeTracker.clearDimension())));
            actions.register(new com.utilitysuite.core.api.SuiteAction("clear_change_snapshots_all",
                    "Clear change snapshots (whole world)", "change tracker forget reset all world",
                    () -> notice(changeTracker.clearAll())));
            actions.register(new com.utilitysuite.core.api.SuiteAction("save_killcam", "Save kill-cam clip",
                    "replay theatre record clip save 30 seconds", () -> notice(replay.saveKillCam())));
        }
        // Created in the registration event (not during parallel mod construction: KeyMapping keeps a global map).
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            KeyMapping zoomKey = key("zoom", InputConstants.KEY_Z);
            KeyMapping addWaypointKey = key("add_waypoint", InputConstants.UNKNOWN.getValue());
            KeyMapping takeMeBackKey = key("take_me_back", InputConstants.UNKNOWN.getValue());
            e.register(zoomKey);
            e.register(addWaypointKey);
            e.register(takeMeBackKey);
            camera.setZoomKey(zoomKey);
            KeyMapping freelookKey = key("freelook", InputConstants.KEY_LALT);
            e.register(freelookKey);
            freelook.setKey(freelookKey);
            waypoints.setKeys(addWaypointKey, takeMeBackKey);
            KeyMapping sonarKey = key("sonar_pulse", InputConstants.UNKNOWN.getValue());
            KeyMapping cavityKey = key("cavity_rescan", InputConstants.UNKNOWN.getValue());
            e.register(sonarKey);
            e.register(cavityKey);
            sonar.setKey(sonarKey);
            cavities.setKey(cavityKey);
            KeyMapping snapshotKey = key("snapshot_chunks", InputConstants.UNKNOWN.getValue());
            KeyMapping changeDiffKey = key("toggle_change_diff", InputConstants.UNKNOWN.getValue());
            e.register(snapshotKey);
            e.register(changeDiffKey);
            changeTracker.setKeys(snapshotKey, changeDiffKey);
            KeyMapping replayFreezeKey = key("replay_freeze", InputConstants.UNKNOWN.getValue());
            KeyMapping parkourStartKey = key("parkour_start", InputConstants.UNKNOWN.getValue());
            KeyMapping parkourFinishKey = key("parkour_finish", InputConstants.UNKNOWN.getValue());
            KeyMapping pipParkKey = key("pip_park", InputConstants.UNKNOWN.getValue());
            e.register(replayFreezeKey);
            e.register(parkourStartKey);
            e.register(parkourFinishKey);
            e.register(pipParkKey);
            replay.setKeys(replayFreezeKey, parkourStartKey, parkourFinishKey);
            pip.setParkKey(pipParkKey);
        });

        // Mixin hooks.
        BlockChangeFeed blockFeed = new BlockChangeFeed();
        Services.registerBlockChanges(blockFeed); // other modules (the route runner) watch cells through core.api
        WorldHooks.setBlockChangeListener((pos, o, n) -> {
            runtime.onBlockChanged(pos, o, n);
            lightOverlay.onBlockChanged(pos, o, n);
            blockFeed.publish(pos.asLong());
        });
        WorldHooks.setTurnRedirect((yRot, xRot) -> camera.turn(yRot, xRot) || freelook.turn(yRot, xRot));

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(runtime::onTick);
        bus.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> freelook.pollKey());
        bus.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> replay.pollKeys());
        bus.addListener(SubmitCustomGeometryEvent.class, runtime::onSubmit);
        bus.addListener(SubmitCustomGeometryEvent.class, projectileRibbon::onRenderLevel); // normal priority: inside the Shell world kit pass
        bus.addListener((ChunkEvent.Load e) -> {
            runtime.onChunkLoad(e);
            if (e.getLevel().isClientSide()) {
                lightOverlay.onChunkLoaded(e.getChunk().getPos().x(), e.getChunk().getPos().z());
                if (e.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
                    seedScanner.onChunkLoaded(cl, e.getChunk());
                }
            }
        });
        bus.addListener((ChunkEvent.Unload e) -> runtime.onChunkUnload(e));
        bus.addListener((EntityJoinLevelEvent e) -> strongholdFinder.onEntityJoin(e));
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> runtime.onWorldUnload());
        bus.addListener((ClientChatReceivedEvent.System e) -> oreSim.onChat(e));
        bus.addListener((MovementInputUpdateEvent e) -> camera.onMovementInput(e));
        bus.addListener((InputEvent.InteractionKeyMappingTriggered e) -> camera.onInteraction(e));
        bus.addListener((InputEvent.MouseScrollingEvent e) -> camera.onScroll(e));
        bus.addListener((RenderHandEvent e) -> camera.onRenderHand(e));
        bus.addListener((ViewportEvent.ComputeFov e) -> camera.onComputeFov(e));
        bus.addListener((ViewportEvent.ComputeCameraAngles e) -> freelook.onCameraAngles(e));
        bus.addListener((ViewportEvent.RenderFog e) -> camera.onFog(e));
        bus.addListener((ViewportEvent.RenderFog e) -> atmosphere.onFog(e));
        bus.addListener((ViewportEvent.ComputeFogColor e) -> atmosphere.onFogColor(e));
        bus.addListener((RenderFrameEvent.Pre e) -> governor.onFrame(e));
        bus.addListener((RenderFrameEvent.Post e) -> pip.onRenderFrame(e)); // PiP full render: second level pass after the main frame
        bus.addListener(RenderLivingEvent.Pre.class, e -> governor.onRenderLiving(e));
        bus.addListener((CalculatePlayerTurnEvent e) -> camera.onTurnSensitivity(e));
        bus.addListener((ExtractBlockScreenEffectEvent e) -> camera.onScreenEffect(e));
        bus.addListener((RenderGuiLayerEvent.Pre e) -> camera.onGuiLayer(e));
    }

    /** Confirmation toast of a suite action (hidden in Clean-Screen, like every notice). */
    private static void notice(String text) {
        if (!Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(com.utilitysuite.core.api.visual.NoticeKind.INFO, text, null);
        }
    }

    private static KeyMapping key(String name, int defaultKey) {
        return new KeyMapping("key.utilitysuite." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYBOARD,
                defaultKey, KeybindManager.CATEGORY);
    }

    /** For diagnostics and tests of the wiring. */
    public static WorldRuntime runtime() {
        return runtime;
    }
}
