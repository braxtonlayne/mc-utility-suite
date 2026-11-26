package com.utilitysuite.core;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.autopilot.Autopilot;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.config.ConfigStore;
import com.utilitysuite.core.config.ModState;
import com.utilitysuite.core.config.ProfileIds;
import com.utilitysuite.core.input.ForeignRotationHook;
import com.utilitysuite.core.input.Humanizer;
import com.utilitysuite.core.input.InputSettings;
import com.utilitysuite.core.input.InputSmoother;
import com.utilitysuite.core.input.PrimitiveRandom;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.core.input.ManualInputHooks;
import com.utilitysuite.core.input.ScreenTextEntry;
import com.utilitysuite.core.input.ManualLookAssist;
import com.utilitysuite.core.input.ManualFactsTracker;
import com.utilitysuite.core.input.MinecraftInputBackend;
import com.utilitysuite.core.input.SharedInputOverlay;
import com.utilitysuite.core.input.YawCoSteer;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * Creates the core services, registers them in {@link Services} and subscribes the core's event handlers. Called first
 * from {@link UtilitySuiteMod}; feature bootstraps run after it and can rely on every core service being registered.
 *
 * <h2>Per-tick order</h2>
 * <ul>
 *   <li>Pre: smoother re-asserts held keys and expires taps; mouse-look accumulation window restarts.</li>
 *   <li>Post: key bindings dispatch, arbiter (timeouts, override pauses), enabled modules, smoother (container click,
 *       silent rotation), config save debounce.</li>
 *   <li>Every frame: VISIBLE camera rotation.</li>
 * </ul>
 */
public final class CoreBootstrap {

    private static SuiteEventBus events;
    private static ModuleRegistry modules;
    private static Autopilot autopilot;
    private static ActionArbiter arbiter;
    private static InputSmoother smoother;
    private static ConfigStore configStore;
    private static ModState modState;
    private static ManualInputHooks manualInput;
    private static Humanizer humanizer;
    private static SharedInputOverlay sharedControl;
    private static long lastFrameNanos;
    /** The one-time 1.21.1 key-code migration ran (first client tick, options.txt loaded by then). */
    private static boolean legacyKeysChecked;

    private CoreBootstrap() {
    }

    public static void init(IEventBus modBus) {
        events = new SuiteEventBus();
        Services.registerEvents(events);

        modules = new ModuleRegistry();
        Services.registerModules(modules);

        // one primitive generator per session: curve shape, click jitter and humanization share the stream
        PrimitiveRandom random = new PrimitiveRandom(PrimitiveRandom.sessionSeed());
        humanizer = new Humanizer(random);
        Services.registerHumanizer(humanizer);
        smoother = new InputSmoother(new MinecraftInputBackend(), random);
        smoother.setHumanizer(humanizer);
        ForeignRotationHook.install(smoother);
        Services.registerInput(smoother);
        Services.registerClicker(smoother);

        arbiter = new ActionArbiter();
        arbiter.bind(smoother, modules, events);
        Services.registerArbiter(arbiter);
        smoother.setCameraHolderLookup(() -> arbiter.holder(ControlChannel.CAMERA).orElse(null));

        manualInput = new ManualInputHooks(arbiter, smoother);
        // turn gain on the user's own mouse: fed by the turn-gain mouse hook, cleared by Stop-All and world unload
        // (both reach InputSmoother.releaseAll), ticked from manualInput.beginTick
        ManualLookAssist manualLook = new ManualLookAssist();
        manualLook.setSmoother(smoother);
        smoother.setManualLookAssist(manualLook);
        manualInput.setManualLookAssist(manualLook);
        Services.registerManualLook(manualLook);
        // control shared with the user: hardware key facts, the one-tick input overlay and the yaw co-steer (fed by
        // the same turn hook, after the gain). Stop-All, world unload and winning leases end the grant in the arbiter.
        ManualFactsTracker facts = new ManualFactsTracker();
        YawCoSteer coSteer = new YawCoSteer();
        manualInput.setManualFacts(facts);
        manualInput.setYawCoSteer(coSteer);
        sharedControl = new SharedInputOverlay(arbiter, facts, coSteer,
                () -> Minecraft.getInstance().gui.screen() != null, CoreBootstrap::legitLevelIsLegit);
        Services.registerSharedControl(sharedControl);

        configStore = new ConfigStore(FMLPaths.CONFIGDIR.get().resolve(UtilitySuiteMod.MOD_ID));
        modState = new ModState(configStore, arbiter, modules);
        modState.setCoreSettingsListener(CoreBootstrap::applyCoreSettings);
        Services.registerModState(modState);
        modState.loadGlobal();

        autopilot = new Autopilot();
        modules.register(autopilot);
        Services.registerAutopilot(autopilot);

        modBus.addListener(KeybindManager::onRegisterKeyMappings);
        KeybindManager.onPressed(KeybindManager.KeyId.TOGGLE_AUTOPILOT, () -> setAutopilot(!autopilot.isEnabled()));
        KeybindManager.onPressed(KeybindManager.KeyId.STOP_ALL, CoreBootstrap::stopAllFromKey);
        KeybindManager.onPressed(KeybindManager.KeyId.CLEAN_SCREEN,
                () -> modState.setCleanScreen(!modState.isCleanScreen()));

        // Named actions (action wheel, command palette). Key-backed ones run the key's handlers, so later modules'
        // handlers (freecam, overlays) are included.
        com.utilitysuite.core.actions.SuiteActionRegistry actions = new com.utilitysuite.core.actions.SuiteActionRegistry();
        Services.registerActions(actions);
        keyAction(actions, KeybindManager.KeyId.STOP_ALL, "stop_all", "Stop-All", "stop cancel halt panic");
        keyAction(actions, KeybindManager.KeyId.CLEAN_SCREEN, "toggle_clean_screen", "Toggle Clean-Screen",
                "streamer hide hud");
        keyAction(actions, KeybindManager.KeyId.TOGGLE_AUTOPILOT, "toggle_autopilot", "Toggle Autopilot", "");
        keyAction(actions, KeybindManager.KeyId.TOGGLE_WORLD_OVERLAYS, "toggle_world_overlays",
                "Toggle World Overlays", "esp boxes");
        keyAction(actions, KeybindManager.KeyId.TOGGLE_SCOUT_CAMERA, "toggle_scout_camera", "Toggle Freecam",
                "scout camera drone");
        keyAction(actions, KeybindManager.KeyId.TOGGLE_TERRAIN_SURFACE, "toggle_terrain_surface",
                "Toggle Terrain Assist", "surface");
        keyAction(actions, KeybindManager.KeyId.OPEN_MENU, "open_menu", "Open Suite Menu", "settings");
        keyAction(actions, KeybindManager.KeyId.COMMAND_PALETTE, "command_palette", "Command Palette", "search");
        keyAction(actions, KeybindManager.KeyId.UNDO_SETTING, "undo_setting", "Undo Last Setting Change", "revert");

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(CoreBootstrap::onTickPre);
        bus.addListener(CoreBootstrap::onTickPost);
        bus.addListener(CoreBootstrap::onFrame);
        // first, before any module reads the keys or the look: the smoother opens this tick's movement input and
        // re-aims the keys after a foreign turn (see setForeignRotationGuard); modules may then ask it for a heading
        // hold (IInputSmoother.holdMoveHeading) from their own listeners
        bus.addListener(EventPriority.HIGHEST, CoreBootstrap::onMovementInputFirst);
        // shared-control writes: after the module listeners that only read the user's keys (NORMAL); then closed
        bus.addListener(EventPriority.LOW, CoreBootstrap::onMovementInputLast);
        bus.addListener((ScreenEvent.Opening e) -> sharedControl.onScreenOpening());
        bus.addListener(CoreBootstrap::onLogin);
        bus.addListener(CoreBootstrap::onLogout);
        bus.addListener(CoreBootstrap::onPlayerSwap);
        bus.addListener(manualInput::onKey);
        bus.addListener(manualInput::onMouseButton);
        bus.addListener(manualInput::onScroll);
        // typed characters a text field (or another mod's overlay, by cancelling) consumed: see TextEntryGuard
        bus.addListener(EventPriority.HIGHEST, true, ScreenTextEntry::onCharTypedPre);
        bus.addListener(ScreenTextEntry::onCharTypedPost);
    }

    /** Re-reads the core settings from {@code global.json} (also after the UI changes one). */
    public static void applyCoreSettings() {
        ProfileConfig g = modState.global();
        arbiter.setManualOverridePauseTicks(g.getInt(ModState.CORE, ModState.KEY_OVERRIDE_PAUSE_TICKS, 20));
        manualInput.setMouseThresholdPx(g.getDouble(ModState.CORE, ModState.KEY_MOUSE_THRESHOLD_PX, 6.0));
        smoother.curve().setBaseSpeed(g.getDouble(ModState.CORE, ModState.KEY_CAMERA_SPEED, 240.0));
        humanizer.setEnabled(g.getBool(ModState.CORE, InputSettings.KEY_HUMANIZATION, true));
        humanizer.setSkillOverride(g.getDouble(ModState.CORE, InputSettings.KEY_SKILL_OVERRIDE, -1.0));
        smoother.setMomentumRelease(g.getBool(ModState.CORE, InputSettings.KEY_MOMENTUM_RELEASE, true));
    }

    /** The autopilot module (opt-in layer over the reflex modules). */
    public static Autopilot autopilot() {
        return autopilot;
    }

    /** Turns the autopilot on or off (key, menu bar), records it in the profile and says so on the action bar. */
    public static void setAutopilot(boolean on) {
        ProfileConfig profile = modState.hasActiveProfile() ? modState.profile() : null;
        modules.setModuleEnabled(Autopilot.ID, on, profile);
        if (profile != null) {
            modState.markDirty();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !modState.isCleanScreen()) {
            mc.player.sendOverlayMessage(Component.translatable(on ? "message.utilitysuite.autopilot_on"
                    : "message.utilitysuite.autopilot_off"));
        }
    }

    /** True when the active profile sits on the LEGIT level (the core then refuses the yaw co-steer). */
    private static boolean legitLevelIsLegit() {
        if (modState == null || !modState.hasActiveProfile()) {
            return false;
        }
        return LegitPresets.current(modState.profile(), modState.global()) == LegitPresets.Level.LEGIT;
    }

    /** Control shared with the user (also in {@link Services#sharedControl()}). */
    public static SharedInputOverlay sharedControl() {
        return sharedControl;
    }

    private static void keyAction(com.utilitysuite.core.api.ISuiteActions actions, KeybindManager.KeyId key,
                                  String id, String label, String keywords) {
        actions.register(new com.utilitysuite.core.api.SuiteAction(id, label, keywords, () -> KeybindManager.fire(key)));
    }

    private static void stopAllFromKey() {
        arbiter.stopAll("Stop-All key");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !modState.isCleanScreen()) {
            mc.player.sendOverlayMessage(Component.translatable("message.utilitysuite.stop_all"));
        }
    }

    // ---- event handlers ---------------------------------------------------------------------------------------------

    private static void onTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        boolean screenOpen = mc.gui.screen() != null;
        manualInput.beginTick(screenOpen);
        ScreenTextEntry.tick(screenOpen);
        sharedControl.beginTick(screenOpen);
        smoother.preTick();
    }

    private static void onTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!legacyKeysChecked) {
            legacyKeysChecked = true;
            if (KeybindManager.migrateLegacyBindings(modState.global())) {
                modState.markDirty();
            }
        }
        if (mc.player != null && mc.level != null) {
            KeybindManager.tick();
            arbiter.tick();
            modules.tick();
            smoother.postTick();
        }
        modState.tick();
    }

    private static void onMovementInputFirst(MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        // the smoother works on player.input, the object this event carries for the local player
        if (event.getEntity() == mc.player && mc.player != null && event.getInput() == mc.player.input) {
            smoother.beginMoveInput();
        }
    }

    private static void onMovementInputLast(MovementInputUpdateEvent event) {
        sharedControl.onMovementInput(event);
        smoother.endMoveInput();
    }

    private static void onFrame(RenderFrameEvent.Pre event) {
        long now = System.nanoTime();
        long last = lastFrameNanos;
        lastFrameNanos = now;
        Minecraft mc = Minecraft.getInstance();
        if (last == 0L || mc.player == null || mc.isPaused()) {
            return;
        }
        smoother.onFrame((now - last) * 1.0e-9);
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        String id = currentProfileId(Minecraft.getInstance());
        UtilitySuiteMod.LOGGER.info("Loading profile {}", id);
        Minecraft mc = Minecraft.getInstance();
        boolean sp = mc.getSingleplayerServer() != null;
        ServerData sd = mc.getCurrentServer();
        // the user's own per-server level (core "server_levels"), written before any module reads the profile
        modState.loadProfile(id, profile -> {
            LegitPresets.Level level = ServerLevels.resolve(modState.global(), sp, sd == null ? null : sd.ip);
            if (level != null) {
                UtilitySuiteMod.LOGGER.info("Server level {} for profile {}", level, id);
                LegitPresets.apply(level, profile, modState.global());
                applyCoreSettings();
            }
        });
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Release every simulated key first so nothing stays pressed into the title screen or the next world.
        arbiter.resetForWorldUnload();
        smoother.releaseAll();
        modules.worldUnloaded();
        if (modState.hasActiveProfile()) {
            modState.unloadProfile();
        }
    }

    /**
     * Respawn and dimension change: {@code ClientPacketListener.handleRespawn} builds a new {@code LocalPlayer} for both
     * and posts Clone (no LoggingOut/LoggingIn), on the client thread, once {@code mc.player} is the new one. Key
     * mappings and the smoother's state are not per player, so a held key, a silent rotation or a reported sneak pose
     * would act on the new player. The world and the profile stay, so modules get no {@code onWorldUnload}; the
     * arbiter ends the leases (holders are notified) and releases all simulated input. The server set the new
     * player's head angle itself, so a silent rotation's eased return is dropped instead of played on the new player.
     */
    private static void onPlayerSwap(ClientPlayerNetworkEvent.Clone event) {
        arbiter.resetForPlayerSwap();
        smoother.dropSilentReturn();
    }

    static String currentProfileId(Minecraft mc) {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            Path root = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path name = root.getFileName();
            return ProfileIds.singleplayer(name == null ? null : name.toString());
        }
        ServerData server = mc.getCurrentServer();
        return ProfileIds.multiplayer(server == null ? null : server.ip);
    }

    // ---- accessors for later bootstraps -----------------------------------------------------------------------------

    public static ModuleRegistry modules() {
        return modules;
    }

    public static ActionArbiter arbiter() {
        return arbiter;
    }

    public static InputSmoother smoother() {
        return smoother;
    }

    public static ModState modState() {
        return modState;
    }

    public static ConfigStore configStore() {
        return configStore;
    }
}
