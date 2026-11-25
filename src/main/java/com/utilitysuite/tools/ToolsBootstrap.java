package com.utilitysuite.tools;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.tools.commands.DotCommands;
import com.utilitysuite.tools.inventory.ContainerButtons;
import com.utilitysuite.tools.inventory.ShulkerPreview;
import com.utilitysuite.tools.inventory.SmartInventory;
import com.utilitysuite.tools.packetlab.LatencySimulator;
import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.tools.player.AutoFish;
import com.utilitysuite.tools.player.AutoReconnect;
import com.utilitysuite.tools.player.AutoRespawn;
import com.utilitysuite.tools.player.OffhandManager;
import com.utilitysuite.tools.player.QuickPearl;
import com.utilitysuite.tools.rules.LocalRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wires the Tools area: SmartInventory (+ container buttons, quick-deposit key, shulker preview), PacketLab (packet
 * inspector + timeline), the local latency simulator and Local Rules. Called from {@code UtilitySuiteMod} after the
 * core and the other feature bootstraps.
 */
public final class ToolsBootstrap {

    private static SmartInventory inventory;
    private static PacketLab packetLab;
    private static LatencySimulator latency;
    private static LocalRules rules;
    private static ContainerButtons containerButtons;
    private static DotCommands dotCommands;
    private static OffhandManager offhand;
    private static AutoFish autoFish;
    private static AutoRespawn autoRespawn;
    private static com.utilitysuite.tools.packetlab.LagGuard lagGuard;
    private static AutoReconnect autoReconnect;
    private static QuickPearl quickPearl;
    private static com.utilitysuite.tools.villager.VillagerHelpers villagerHelpers;

    private ToolsBootstrap() {
    }

    public static void init(IEventBus modBus) {
        inventory = new SmartInventory();
        packetLab = new PacketLab();
        latency = new LatencySimulator();
        rules = new LocalRules(ToolsBootstrap::inventory, ToolsBootstrap::quickPearl);

        Services.modules().register(inventory);
        Services.modules().register(packetLab);
        Services.modules().register(latency);
        Services.modules().register(rules);
        lagGuard = new com.utilitysuite.tools.packetlab.LagGuard();
        Services.modules().register(lagGuard);
        Services.registerServerHealth(lagGuard);

        packetLab.subscribe(Services.events());
        rules.subscribe(Services.events());

        containerButtons = new ContainerButtons(ToolsBootstrap::inventory);
        ShulkerPreview shulker = new ShulkerPreview(ToolsBootstrap::inventory);
        modBus.addListener(containerButtons::onRegisterKeys);
        modBus.addListener(shulker::onRegisterFactories);

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(containerButtons::onScreenInit);
        bus.addListener(containerButtons::onKeyPressed);
        bus.addListener(shulker::onGather);
        bus.addListener(packetLab::onLogin);
        bus.addListener(packetLab::onChunkLoad);

        initPlayerTools(modBus, bus);
    }

    /** Player and misc tools: dot-commands, offhand manager, auto-fish, auto-respawn, auto-reconnect, quick pearl. */
    private static void initPlayerTools(IEventBus modBus, IEventBus bus) {
        dotCommands = new DotCommands();
        offhand = new OffhandManager();
        autoFish = new AutoFish();
        autoRespawn = new AutoRespawn();
        autoReconnect = new AutoReconnect();
        quickPearl = new QuickPearl();

        Services.modules().register(dotCommands);
        Services.modules().register(offhand);
        Services.modules().register(autoFish);
        Services.modules().register(autoRespawn);
        Services.modules().register(autoReconnect);
        Services.modules().register(quickPearl);
        Services.modules().register(new com.utilitysuite.tools.enchant.EnchantSeedHelper());

        modBus.addListener(quickPearl::onRegisterKeys);

        bus.addListener(EventPriority.HIGHEST, dotCommands::onClientChat);
        bus.addListener(offhand::onClientTick);
        bus.addListener(autoFish::onPlaySound);
        bus.addListener(autoRespawn::onScreenOpening);
        bus.addListener(autoReconnect::onLoggingIn);
        bus.addListener(autoReconnect::onLoggingOut);
        bus.addListener(autoReconnect::onScreenInit);
        bus.addListener(autoReconnect::onScreenRender);
        bus.addListener(autoReconnect::onClientTick);
        bus.addListener(quickPearl::onClientTick);

        villagerHelpers = new com.utilitysuite.tools.villager.VillagerHelpers();
        Services.modules().register(villagerHelpers);
        modBus.addListener(villagerHelpers::onRegisterKeys);
        bus.addListener(villagerHelpers::onKeyPressed);
    }

    public static com.utilitysuite.tools.villager.VillagerHelpers villagerHelpers() {
        return villagerHelpers;
    }

    public static DotCommands dotCommands() {
        return dotCommands;
    }

    public static OffhandManager offhand() {
        return offhand;
    }

    public static AutoFish autoFish() {
        return autoFish;
    }

    public static AutoReconnect autoReconnect() {
        return autoReconnect;
    }

    public static QuickPearl quickPearl() {
        return quickPearl;
    }

    public static SmartInventory inventory() {
        return inventory;
    }

    public static PacketLab packetLab() {
        return packetLab;
    }

    public static LatencySimulator latency() {
        return latency;
    }

    public static LocalRules rules() {
        return rules;
    }

    public static ContainerButtons containerButtons() {
        return containerButtons;
    }
}
