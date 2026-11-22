package com.utilitysuite.travel;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.travel.autopilot.TravelAutopilot;
import com.utilitysuite.travel.bridge.BridgeAssist;
import com.utilitysuite.travel.mobility.ElytraSwap;
import com.utilitysuite.travel.mobility.Fly;
import com.utilitysuite.travel.mobility.MountJump;
import com.utilitysuite.travel.mobility.MoveToggles;
import com.utilitysuite.travel.mobility.VelocityScale;
import com.utilitysuite.travel.terrain.TerrainAssist;
import com.utilitysuite.travel.terrain.TerrainHooks;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wires the travel modules: Terrain Assist, Travel Autopilot and Bridge Assist. Registers them in the module registry,
 * binds the mixin hooks, the surface-mode key (core {@code TOGGLE_TERRAIN_SURFACE}), the travel keys, the hazard
 * renderer, the movement-input hook (NoSlowdown) and the position-correction listener.
 */
public final class TravelBootstrap {

    private static TerrainAssist terrain;
    private static TravelAutopilot autopilot;
    private static BridgeAssist bridge;
    private static com.utilitysuite.travel.movement.MovementAssist movement;
    // Built once so the per-tick key dispatch allocates nothing (a bound method reference allocates per evaluation).
    private static Runnable onAutopilotKey;
    private static Runnable onBridgeKey;
    private static Runnable onBridgeMarkKey;

    private TravelBootstrap() {
    }

    public static void init(IEventBus modBus) {
        terrain = new TerrainAssist();
        autopilot = new TravelAutopilot();
        bridge = new BridgeAssist();
        Services.modules().register(terrain);
        Services.modules().register(autopilot);
        movement = new com.utilitysuite.travel.movement.MovementAssist();
        Services.modules().register(bridge);
        Services.modules().register(movement);
        // Shared forward simulation (core.api.BodySim) for modules outside travel, e.g. the combat hit-trade solver.
        com.utilitysuite.travel.movement.WorldBodySims bodySims = new com.utilitysuite.travel.movement.WorldBodySims();
        Services.registerBodySims(bodySims);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Pre.class,
                event -> bodySims.beginTick());
        com.utilitysuite.travel.pursuit.MinecraftRouteRunner routeRunner =
                new com.utilitysuite.travel.pursuit.MinecraftRouteRunner();
        Services.registerRouteRunner(routeRunner);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, routeRunner::onTick);
        onAutopilotKey = autopilot::toggle;
        onBridgeKey = bridge::toggle;
        onBridgeMarkKey = bridge::mark;

        TerrainHooks.bind(terrain);
        KeybindManager.onPressed(KeybindManager.KeyId.TOGGLE_TERRAIN_SURFACE, terrain::toggleSurfaceMode);
        modBus.addListener(TravelKeys::onRegisterKeyMappings);
        Services.events().subscribe(SuiteEvent.PositionCorrection.class, terrain::onPositionCorrection);

        NeoForge.EVENT_BUS.addListener(terrain::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(terrain::onMovementInput);
        movement.setSafeWalk(terrain::isSafeWalkActive);
        terrain.setParkourJumping(movement::jumpingThisTick);
        NeoForge.EVENT_BUS.addListener(movement::onMovementInput);
        NeoForge.EVENT_BUS.addListener(movement::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(TravelBootstrap::onTickPost);

        // mobility modules (all off by default)
        elytraSwap = new ElytraSwap();
        toggles = new MoveToggles();
        mountJump = new MountJump();
        fly = new Fly();
        velocity = new VelocityScale();
        Services.modules().register(elytraSwap);
        Services.modules().register(toggles);
        Services.modules().register(mountJump);
        Services.modules().register(fly);
        Services.modules().register(velocity);
        onToggleSprint = toggles::toggleSprint;
        onToggleSneak = toggles::toggleSneak;
        NeoForge.EVENT_BUS.addListener(toggles::onMovementInput);
        NeoForge.EVENT_BUS.addListener(mountJump::onMovementInput);
    }

    private static ElytraSwap elytraSwap;
    private static MoveToggles toggles;
    private static MountJump mountJump;
    private static Fly fly;
    private static VelocityScale velocity;
    private static Runnable onToggleSprint;
    private static Runnable onToggleSneak;

    private static void onTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        TravelKeys.tick(onAutopilotKey, onBridgeKey, onBridgeMarkKey);
        TravelKeys.tickToggles(onToggleSprint, onToggleSneak);
        elytraSwap.tick(); // also the autopilot's ELYTRA_SWAP reflex while the module is off
    }

    public static TerrainAssist terrain() {
        return terrain;
    }

    public static TravelAutopilot autopilot() {
        return autopilot;
    }

    public static BridgeAssist bridge() {
        return bridge;
    }

    public static com.utilitysuite.travel.movement.MovementAssist movement() {
        return movement;
    }
}
