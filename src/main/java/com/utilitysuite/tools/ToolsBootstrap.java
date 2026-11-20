package com.utilitysuite.tools;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.tools.inventory.ContainerButtons;
import com.utilitysuite.tools.inventory.ShulkerPreview;
import com.utilitysuite.tools.inventory.SmartInventory;
import com.utilitysuite.tools.packetlab.LatencySimulator;
import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.tools.rules.LocalRules;
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

    private ToolsBootstrap() {
    }

    public static void init(IEventBus modBus) {
        inventory = new SmartInventory();
        packetLab = new PacketLab();
        latency = new LatencySimulator();
        rules = new LocalRules(ToolsBootstrap::inventory);

        Services.modules().register(inventory);
        Services.modules().register(packetLab);
        Services.modules().register(latency);
        Services.modules().register(rules);

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
