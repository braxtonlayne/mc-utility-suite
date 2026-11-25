package com.utilitysuite.combat;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.combat.allies.AllyPickHandler;
import com.utilitysuite.combat.allies.AllyRosterModule;
import com.utilitysuite.combat.allies.AllyRosterStore;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.mc.ArenaVoxels;
import com.utilitysuite.combat.assistant.mc.CombatEventBridge;
import com.utilitysuite.combat.assistant.mc.MinecraftCombatWorld;
import com.utilitysuite.combat.assistant.mc.PearlWatch;
import com.utilitysuite.combat.coach.FightCoach;
import com.utilitysuite.combat.coach.FightCoachHud;
import com.utilitysuite.combat.assistant.mc.McHealFacts;
import com.utilitysuite.combat.assistant.mc.McNearbyThreats;
import com.utilitysuite.combat.assistant.mc.McShieldFacts;
import com.utilitysuite.combat.assistant.stages.ComboEscapeStage;
import com.utilitysuite.combat.assistant.stages.HealWindowStage;
import com.utilitysuite.combat.assistant.stages.ShieldDuelStage;
import com.utilitysuite.combat.assistant.stages.ThreatFightStage;
import com.utilitysuite.combat.coach.ThreatTracker;
import com.utilitysuite.combat.hud.ReadRingModule;
import com.utilitysuite.combat.hud.TargetHud;
import com.utilitysuite.combat.reflex.CombatReflexes;
import com.utilitysuite.combat.tactics.ReadState;
import com.utilitysuite.combat.trigger.TriggerAssist;
import com.utilitysuite.core.api.Services;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * Wires the combat area: Combat Assistant, Ally Roster and Fight Coach modules, their client event listeners, the
 * assistant's two key bindings and the coach's HUD layer. Called from {@link UtilitySuiteMod} after the core.
 */
public final class CombatBootstrap {

    private static CombatAssistant assistant;
    private static AllyRosterModule allies;
    private static FightCoach coach;
    private static ThreatTracker threats;
    private static CombatReflexes reflexes;
    private static TriggerAssist trigger;
    private static TargetHud targetHud;
    private static PearlWatch pearls;
    private static ReadState readState;
    private static ReadRingModule readRing;

    private CombatBootstrap() {
    }

    public static void init(IEventBus modBus) {
        Path alliesDir = FMLPaths.CONFIGDIR.get().resolve(UtilitySuiteMod.MOD_ID).resolve("combat").resolve("allies");
        allies = new AllyRosterModule(new AllyRosterStore(alliesDir));
        MinecraftCombatWorld world = new MinecraftCombatWorld();
        assistant = new CombatAssistant(world, allies.roster(), Services.arbiter(), Services.input(),
                Services.clicker(), System::currentTimeMillis);
        // pro-feel units: the arena read and pearl landings over the live level, pearls from entity events, the read
        pearls = new PearlWatch();
        readState = new ReadState();
        assistant.useArenaVoxels(new ArenaVoxels());
        assistant.usePearlSource(pearls);
        assistant.useReadState(readState);
        readRing = new ReadRingModule(readState);
        threats = new ThreatTracker();
        coach = new FightCoach(world, assistant, allies.roster(), threats,
                () -> Services.modState().isCleanScreen());

        reflexes = new CombatReflexes(assistant, threats, world);
        trigger = new TriggerAssist(world, allies.roster());
        targetHud = new TargetHud(world, assistant, allies.roster());

        // fight stages (settings must be in the module's list before it is registered)
        assistant.addStage(new ThreatFightStage(new McNearbyThreats())); // PRE_AIM
        assistant.addStage(new com.utilitysuite.combat.assistant.stages.RangedShotStage(
                new com.utilitysuite.combat.assistant.mc.McRangedFacts()));  // PRE_AIM, off unless ranged_aim
        ShieldDuelStage shieldDuel = new ShieldDuelStage(new McShieldFacts());
        assistant.addStage(shieldDuel);                                  // PRE_SWING
        reflexes.feedShieldStage(shieldDuel.threatFeed()); // one USE owner in a fight; the reflex keeps sensing
        assistant.addStage(new HealWindowStage(new McHealFacts()));
        assistant.addStage(new ComboEscapeStage());                      // MOVEMENT
        Services.modules().register(assistant);
        Services.modules().register(coach);
        Services.modules().register(allies);
        Services.modules().register(reflexes);
        Services.modules().register(trigger);
        Services.modules().register(targetHud);
        Services.modules().register(readRing);
        assistant.bindModuleRegistry(Services.modules());

        CombatEventBridge.install(assistant);
        AllyPickHandler pick = new AllyPickHandler(allies);
        FightCoachHud hud = new FightCoachHud(coach);
        hud.useThreats(threats); // threats are published as screen marks instead of the old arrow

        modBus.addListener(CombatEventBridge::onRegisterKeys);
        modBus.addListener(RegisterGuiLayersEvent.class, event -> event.registerAbove(VanillaGuiLayers.CROSSHAIR,
                ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "fight_coach"), hud));

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(CombatEventBridge::onAttack);
        bus.addListener(CombatEventBridge::onKey);
        bus.addListener(CombatEventBridge::onMouseButton);
        bus.addListener(pick::onInteraction);
        bus.addListener(threats::onJoin);
        bus.addListener(threats::onLeave);
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> threats.clear());
        bus.addListener(pearls::onJoin);
        bus.addListener(pearls::onLeave);
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> pearls.clear());
        bus.addListener(readRing::onRenderLevel);
        // the autopilot's totem reflex runs here while the Combat Assistant module itself is off
        bus.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, event -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && mc.level != null) {
                assistant.autopilotTick();
                assistant.handOverTick(); // the core's hand-over key: engage while held, let go on release
            }
            reflexes.clientTick();
        });
        bus.addListener(targetHud::onJoin);
        bus.addListener(targetHud::onLeave);
        modBus.addListener(RegisterGuiLayersEvent.class, event -> event.registerAbove(VanillaGuiLayers.CROSSHAIR,
                ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "target_hud"), targetHud::render));
    }

    public static CombatReflexes reflexes() {
        return reflexes;
    }

    public static ReadRingModule readRing() {
        return readRing;
    }

    public static TargetHud targetHud() {
        return targetHud;
    }

    public static CombatAssistant assistant() {
        return assistant;
    }

    public static AllyRosterModule allies() {
        return allies;
    }

    public static FightCoach coach() {
        return coach;
    }
}
