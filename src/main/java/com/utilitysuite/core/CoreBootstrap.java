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
import net.minecraft.client.player.Input;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
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
                () -> Minecraft.getInstance().screen != null, CoreBootstrap::legitLevelIsLegit);
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

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(CoreBootstrap::onTickPre);
        bus.addListener(CoreBootstrap::onTickPost);
        bus.addListener(CoreBootstrap::onFrame);
        // first, before any module reads the keys or the look: undo foreign turns (see setForeignRotationGuard)
        bus.addListener(EventPriority.HIGHEST, CoreBootstrap::onMovementInput);
        // shared-control writes: after the module listeners that only read the user's keys (NORMAL)
        bus.addListener(EventPriority.LOW, sharedControl::onMovementInput);
        bus.addListener((ScreenEvent.Opening e) -> sharedControl.onScreenOpening());
        bus.addListener(CoreBootstrap::onLogin);
        bus.addListener(CoreBootstrap::onLogout);
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
            mc.player.displayClientMessage(Component.translatable(on ? "message.utilitysuite.autopilot_on"
                    : "message.utilitysuite.autopilot_off"), true);
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

    private static void stopAllFromKey() {
        arbiter.stopAll("Stop-All key");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !modState.isCleanScreen()) {
            mc.player.displayClientMessage(Component.translatable("message.utilitysuite.stop_all"), true);
        }
    }

    // ---- event handlers ---------------------------------------------------------------------------------------------

    private static void onTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        boolean screenOpen = mc.screen != null;
        manualInput.beginTick(screenOpen);
        ScreenTextEntry.tick(screenOpen);
        sharedControl.beginTick(screenOpen);
        smoother.preTick();
    }

    private static void onTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) {
            KeybindManager.tick();
            arbiter.tick();
            modules.tick();
            smoother.postTick();
        }
        modState.tick();
    }

    private static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getEntity() != mc.player || !smoother.isForeignRotationGuardOn()) {
            return;
        }
        float heading = smoother.foreignHeading();
        if (!Float.isNaN(heading)) {
            // keys were meant along the wanted heading; the camera still looks the old way
            Input in = event.getInput();
            double d = Math.toRadians(Mth.wrapDegrees(mc.player.getYRot() - heading));
            double f = in.forwardImpulse;
            double s = in.leftImpulse;
            double cos = Math.cos(d);
            double sin = Math.sin(d);
            in.forwardImpulse = (float) (f * cos - s * sin);
            in.leftImpulse = (float) (s * cos + f * sin);
            in.up = in.forwardImpulse > 0.05f;
            in.down = in.forwardImpulse < -0.05f;
            in.left = in.leftImpulse > 0.05f;
            in.right = in.leftImpulse < -0.05f;
        }
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
        modState.loadProfile(id);
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
