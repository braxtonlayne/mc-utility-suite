package com.utilitysuite.devtest;

import com.utilitysuite.tools.ToolsBootstrap;
import com.utilitysuite.tools.inventory.SmartInventory;
import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.tools.packetlab.PacketRingBuffer;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.client.ClientHooks;

import java.util.List;
import java.util.TreeSet;

/** Scenario 10: SmartInventory hotbar refill (pinned slot keeps its item), shulker tooltip, PacketLab ring buffer. */
final class ToolsScenario {

    private ToolsScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("10-tools", "Tools: refill, shulker preview, PacketLab");
        s.run("enable packet lab", () -> H.enable(PacketLab.ID, true));
        s.cmd("clear @s");
        s.run("refill settings", () -> {
            H.setting(SmartInventory.ID, SmartInventory.K_REFILL, true);
            H.setting(SmartInventory.ID, SmartInventory.K_REFILL_BLOCKS, true);
            H.setting(SmartInventory.ID, SmartInventory.K_PINNED, "2");
            H.enable(SmartInventory.ID, true);
        });
        s.cmd("item replace entity @s inventory.0 with minecraft:cobblestone 64");
        s.cmd("item replace entity @s inventory.1 with minecraft:dirt 64");
        s.cmd("item replace entity @s hotbar.1 with minecraft:dirt 1");
        s.cmd("item replace entity @s hotbar.0 with minecraft:cobblestone 2");
        s.waitUpTo("refill", 20 * 6, () -> H.p().getInventory().getItem(0).getCount() > 2);
        s.waitTicks(20);
        s.run("refill result", () -> {
            var inv = H.p().getInventory();
            ItemStack h0 = inv.getItem(0);
            ItemStack h1 = inv.getItem(1);
            s.check("hotbar refill from main inventory", h0.is(Items.COBBLESTONE) && h0.getCount() > 2,
                    "hotbar.0 = " + h0 + ", cobblestone total " + H.count("minecraft:cobblestone"));
            // By design a pinned slot is refilled with its own item; it is never given another item.
            s.check("pinned slot 2 keeps its own item (dirt)", h1.is(Items.DIRT), "hotbar.1 = " + h1);
        });
        s.run("shulker tooltip", () -> {
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND,
                    3), new ItemStack(Items.TORCH, 16))));
            List<ClientTooltipComponent> comps = ClientHooks.gatherTooltipComponents(box,
                    List.of(Component.literal("Shulker Box")), 0, 400, 300, H.mc().font);
            ClientTooltipComponent found = null;
            for (ClientTooltipComponent c : comps) {
                if (c.getClass().getName().contains("ShulkerPreview")) {
                    found = c;
                }
            }
            s.check("shulker preview tooltip component builds", found != null, found == null
                    ? "components: " + comps : "component " + found.getClass().getSimpleName() + " "
                    + found.getWidth(H.mc().font) + "x" + found.getHeight(H.mc().font));
        });
        s.cmd("tp @s ~ ~ ~ 90 0");
        s.waitTicks(100);
        s.run("packet lab", () -> {
            PacketLab lab = ToolsBootstrap.packetLab();
            PacketRingBuffer ring = lab.ring();
            int max = 512;
            long[] time = new long[max];
            int[] type = new int[max];
            int[] size = new int[max];
            byte[] dir = new byte[max];
            int n = ring.snapshot(max, time, type, size, dir);
            TreeSet<String> names = new TreeSet<>();
            int unknown = 0;
            int in = 0;
            long bytes = 0;
            for (int i = 0; i < n; i++) {
                names.add(lab.types().name(type[i]));
                if (size[i] == PacketRingBuffer.UNKNOWN_SIZE) {
                    unknown++;
                } else {
                    bytes += size[i];
                }
                if (dir[i] == PacketRingBuffer.INBOUND) {
                    in++;
                }
            }
            s.check("PacketLab ring buffer fills with packet types", ring.written() > 20 && names.size() >= 3,
                    "written " + ring.written() + " (capacity " + ring.capacity() + "), last " + n + ": " + in
                            + " in / " + (n - in) + " out, " + names.size() + " types e.g. "
                            + names.stream().limit(8).toList() + "; sizes: " + unknown + " unknown, " + bytes
                            + " bytes known");
        });
        s.always("cleanup", () -> {
            H.setting(SmartInventory.ID, SmartInventory.K_PINNED, "");
            H.enable(PacketLab.ID, false);
        });
        return s;
    }
}
