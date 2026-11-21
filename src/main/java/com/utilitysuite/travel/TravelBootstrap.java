package com.utilitysuite.travel;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.travel.autopilot.TravelAutopilot;
import com.utilitysuite.travel.bridge.BridgeAssist;
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
    }

    private static void onTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        TravelKeys.tick(onAutopilotKey, onBridgeKey, onBridgeMarkKey);
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
