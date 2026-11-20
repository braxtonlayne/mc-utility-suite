package com.utilitysuite.combat;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.combat.allies.AllyPickHandler;
import com.utilitysuite.combat.allies.AllyRosterModule;
import com.utilitysuite.combat.allies.AllyRosterStore;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.mc.CombatEventBridge;
import com.utilitysuite.combat.assistant.mc.MinecraftCombatWorld;
import com.utilitysuite.combat.coach.FightCoach;
import com.utilitysuite.combat.coach.FightCoachHud;
import com.utilitysuite.combat.coach.ThreatTracker;
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

    private CombatBootstrap() {
    }

    public static void init(IEventBus modBus) {
        Path alliesDir = FMLPaths.CONFIGDIR.get().resolve(UtilitySuiteMod.MOD_ID).resolve("combat").resolve("allies");
        allies = new AllyRosterModule(new AllyRosterStore(alliesDir));
        MinecraftCombatWorld world = new MinecraftCombatWorld();
        assistant = new CombatAssistant(world, allies.roster(), Services.arbiter(), Services.input(),
                Services.clicker(), System::currentTimeMillis);
        threats = new ThreatTracker();
        coach = new FightCoach(world, assistant, allies.roster(), threats,
                () -> Services.modState().isCleanScreen());

        Services.modules().register(assistant);
        Services.modules().register(coach);
        Services.modules().register(allies);

        CombatEventBridge.install(assistant);
        AllyPickHandler pick = new AllyPickHandler(allies);
        FightCoachHud hud = new FightCoachHud(coach);

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
