package com.utilitysuite.devtest;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.tools.inventory.SmartInventory;
import com.utilitysuite.tools.packetlab.LatencySimulator;
import com.utilitysuite.tools.player.AutoReconnect;
import com.utilitysuite.tools.player.QuickPearl;
import com.utilitysuite.tools.rules.LocalRules;
import com.utilitysuite.tools.rules.Rule;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Quick Pearl, Local Rules, Auto-Reconnect and the Latency Simulator for {@link ToolsExtraScenario}. */
final class ToolsExtraPlayerTests {

    private static final String LATENCY_HANDLER = "utilitysuite_latency_sim";

    private ToolsExtraPlayerTests() {
    }

    static void add(Scenario s) {
        quickPearl(s);
        localRules(s);
        autoReconnect(s);
        latency(s);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static String tpLook(float yaw, float pitch) {
        return "tp @s " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 0.5) + " " + yaw + " " + pitch;
    }

    private static ThrownEnderpearl pearlNear() {
        for (Entity e : H.mc().level.entitiesForRendering()) {
            if (e instanceof ThrownEnderpearl tp && tp.distanceToSqr(H.p()) < 30 * 30) {
                return tp;
            }
        }
        return null;
    }

    private static boolean pearlCooldown() {
        return H.p().getCooldowns().isOnCooldown(Items.ENDER_PEARL);
    }

    private static boolean quickPearlIdle() {
        Object seq = ToolsExtraScenario.field(H.module(QuickPearl.ID), "seq");
        return "IDLE".equals(String.valueOf(ToolsExtraScenario.field(seq, "phase")));
    }

    /** Non-empty slots as "index:item xcount", plus the selected slot. Diagnostics only. */
    private static String invState(net.minecraft.world.entity.player.Inventory inv) {
        StringBuilder sb = new StringBuilder("sel ").append(inv.selected);
        for (int i = 0; i < 36; i++) {
            var st = inv.getItem(i);
            if (!st.isEmpty()) {
                sb.append(' ').append(i).append(':').append(st.getItem().toString().replace("minecraft:", ""))
                        .append('x').append(st.getCount());
            }
        }
        return sb.toString();
    }

    private static String serverInvState() {
        var server = H.mc().getSingleplayerServer();
        var sp = server == null ? null : server.getPlayerList().getPlayer(H.p().getUUID());
        return sp == null ? "?" : invState(sp.getInventory());
    }

    private static boolean holds(ControlChannel c, String moduleId) {
        return Services.arbiter().isHeldBy(c, moduleId);
    }

    // ---- Quick Pearl ------------------------------------------------------------------------------------------------

    private static void quickPearl(Scenario s) {
        boolean[] requested = new boolean[1];
        boolean[] sawPearl = new boolean[1];
        boolean[] sawSelect = new boolean[1];
        boolean[] sawLease = new boolean[1];
        int[] before = new int[1];

        // SmartInventory's pearl refill (on by default) would refill hotbar slot 5 from the main inventory once the test
        // clears it, racing the inventory-swap case below: off for this section only.
        s.run("QP pearl refill off for the section", () -> H.setting(SmartInventory.ID,
                SmartInventory.K_REFILL_PEARLS, false));
        // 1) pearl in another hotbar slot: select it through the lease, throw, reselect the previous slot
        s.cmd("clear @s");
        s.cmd("item replace entity @s hotbar.0 with minecraft:stick 1");
        s.cmd("item replace entity @s hotbar.4 with minecraft:ender_pearl 3");
        s.cmd(() -> tpLook(0f, -35f));
        s.run("QP select slot 1", () -> H.p().getInventory().selected = 0);
        s.waitTicks(5);
        s.run("QP request throw (hotbar pearl)", () -> {
            before[0] = H.count("minecraft:ender_pearl");
            sawPearl[0] = sawSelect[0] = sawLease[0] = false;
            requested[0] = H.<QuickPearl>module(QuickPearl.ID).requestThrow();
        });
        s.waitUpTo("QP hotbar throw finishes", 60, () -> {
            sawPearl[0] |= pearlNear() != null;
            sawSelect[0] |= H.p().getInventory().selected == 4;
            sawLease[0] |= holds(ControlChannel.HOTBAR, QuickPearl.ID);
            return sawPearl[0] && quickPearlIdle() && H.count("minecraft:ender_pearl") == before[0] - 1
                    && H.p().getInventory().selected == 0;
        });
        s.run("QP hotbar throw check", () -> {
            var inv = H.p().getInventory();
            int after = H.count("minecraft:ender_pearl");
            s.check("Quick Pearl throws a hotbar pearl under its lease and reselects the previous slot",
                    requested[0] && sawPearl[0] && sawSelect[0] && sawLease[0] && after == before[0] - 1
                            && inv.selected == 0 && inv.getItem(0).is(Items.STICK) && pearlCooldown()
                            && !holds(ControlChannel.HOTBAR, QuickPearl.ID)
                            && !holds(ControlChannel.WORLD_INTERACT, QuickPearl.ID),
                    "requested " + requested[0] + ", pearl entity seen " + sawPearl[0] + ", slot 5 selected during run "
                            + sawSelect[0] + ", lease seen " + sawLease[0] + ", pearls " + before[0] + " -> " + after
                            + ", selected now " + (inv.selected + 1) + " holding " + inv.getItem(0) + ", cooldown "
                            + pearlCooldown() + "; " + H.arbiterState());
        });
        s.cmd("kill @e[type=minecraft:ender_pearl]");
        s.cmd(() -> tpLook(0f, -35f));
        s.waitUpTo("QP cooldown over (1)", 60, () -> !pearlCooldown());

        // 2) pearl only in the main inventory: swapped into the selected slot, thrown, swapped back
        s.cmd("clear @s");
        s.cmd("item replace entity @s hotbar.0 with minecraft:stick 1");
        s.cmd("item replace entity @s inventory.11 with minecraft:ender_pearl 2"); // inventory index 20
        s.run("QP select slot 1 again", () -> H.p().getInventory().selected = 0);
        s.waitTicks(5);
        StringBuilder trace = new StringBuilder();
        String[] lastState = {""};
        s.run("QP request throw (inventory pearl)", () -> {
            before[0] = H.count("minecraft:ender_pearl");
            sawPearl[0] = sawSelect[0] = sawLease[0] = false;
            trace.setLength(0);
            lastState[0] = "";
            trace.append("client ").append(invState(H.p().getInventory())).append(" | server ")
                    .append(serverInvState());
            requested[0] = H.<QuickPearl>module(QuickPearl.ID).requestThrow();
        });
        s.waitUpTo("QP swap throw finishes", 80, () -> {
            var inv = H.p().getInventory();
            String st = invState(inv) + " phase " + ToolsExtraScenario.field(
                    ToolsExtraScenario.field(H.module(QuickPearl.ID), "seq"), "phase");
            if (!st.equals(lastState[0])) {
                trace.append(" ; ").append(st);
                lastState[0] = st;
            }
            sawPearl[0] |= pearlNear() != null;
            sawSelect[0] |= inv.getItem(0).is(Items.ENDER_PEARL); // the pearl stack sat in the selected slot
            sawLease[0] |= holds(ControlChannel.HOTBAR, QuickPearl.ID);
            return sawPearl[0] && quickPearlIdle() && inv.getItem(0).is(Items.STICK)
                    && H.count("minecraft:ender_pearl") == before[0] - 1;
        });
        s.run("QP swap throw check", () -> {
            var inv = H.p().getInventory();
            s.check("Quick Pearl swaps an inventory pearl in, throws it and swaps the slot's own stack back",
                    requested[0] && sawPearl[0] && sawSelect[0] && sawLease[0] && inv.selected == 0
                            && inv.getItem(0).is(Items.STICK) && inv.getItem(20).is(Items.ENDER_PEARL)
                            && inv.getItem(20).getCount() == 1 && !holds(ControlChannel.HOTBAR, QuickPearl.ID),
                    "requested " + requested[0] + ", pearl entity seen " + sawPearl[0] + ", pearl seen in slot 1 "
                            + sawSelect[0] + ", lease seen " + sawLease[0] + ", slot 1 " + inv.getItem(0)
                            + ", inventory 20 " + inv.getItem(20) + ", selected " + (inv.selected + 1) + "; "
                            + H.arbiterState() + "; trace " + trace);
        });
        s.cmd("kill @e[type=minecraft:ender_pearl]");
        s.cmd(() -> tpLook(0f, -35f));
        s.waitUpTo("QP cooldown over (2)", 60, () -> !pearlCooldown());

        // 3) manual input wins: the user presses number key 3 while the swapped-in pearl waits for its throw. The run
        // ends without a throw, the user's slot stays selected and the swapped stack goes back afterwards.
        boolean[] pressed = new boolean[1];
        s.cmd("clear @s");
        s.cmd("item replace entity @s hotbar.0 with minecraft:stick 1");
        s.cmd("item replace entity @s inventory.11 with minecraft:ender_pearl 2");
        s.run("QP select slot 1 (manual)", () -> H.p().getInventory().selected = 0);
        s.waitTicks(5);
        s.run("QP request throw (user interrupts)", () -> {
            before[0] = H.count("minecraft:ender_pearl");
            sawPearl[0] = pressed[0] = false;
            requested[0] = H.<QuickPearl>module(QuickPearl.ID).requestThrow();
        });
        s.waitUpTo("QP pearl swapped in, user presses 3", 40, () -> {
            if (!pressed[0] && H.p().getInventory().getItem(0).is(Items.ENDER_PEARL)) {
                H.physicalKey(GLFW.GLFW_KEY_3, true);
                H.physicalKey(GLFW.GLFW_KEY_3, false);
                pressed[0] = true;
            }
            sawPearl[0] |= pearlNear() != null;
            return pressed[0];
        });
        s.waitUpTo("QP swap back after the user's pause", 120, () -> {
            sawPearl[0] |= pearlNear() != null;
            var inv = H.p().getInventory();
            return quickPearlIdle() && inv.getItem(0).is(Items.STICK) && inv.getItem(20).is(Items.ENDER_PEARL)
                    && !Services.arbiter().isManualOverrideActive(ControlChannel.HOTBAR);
        });
        s.waitTicks(5);
        s.run("QP manual input check", () -> {
            var inv = H.p().getInventory();
            s.check("the user's number key ends a quick-pearl run: no throw, the user's slot kept, the swapped stack"
                            + " put back",
                    requested[0] && pressed[0] && !sawPearl[0] && H.count("minecraft:ender_pearl") == before[0]
                            && inv.selected == 2 && inv.getItem(0).is(Items.STICK)
                            && inv.getItem(20).is(Items.ENDER_PEARL) && !pearlCooldown()
                            && !holds(ControlChannel.HOTBAR, QuickPearl.ID),
                    "requested " + requested[0] + ", key 3 pressed with the pearl in slot 1 " + pressed[0]
                            + ", pearl thrown " + sawPearl[0] + ", pearls " + before[0] + " -> "
                            + H.count("minecraft:ender_pearl") + ", selected " + (inv.selected + 1) + ", slot 1 "
                            + inv.getItem(0) + ", inventory 20 " + inv.getItem(20) + ", notices "
                            + ToolsExtraScenario.notices() + "; " + H.arbiterState());
        });
        s.run("QP back to slot 1", () -> H.p().getInventory().selected = 0);
        s.cmd("kill @e[type=minecraft:ender_pearl]");
        s.cmd(() -> tpLook(0f, 0f));
        s.waitUpTo("QP cooldown over (3)", 60, () -> !pearlCooldown());

        // 4) a wall right in front of the eyes: the throw is held and nothing is selected
        s.cmd("clear @s");
        s.cmd("item replace entity @s hotbar.0 with minecraft:stick 1");
        s.cmd("item replace entity @s hotbar.4 with minecraft:ender_pearl 2");
        s.cmd(() -> Arena.fill(-1, 1, 1, 1, 3, 1, "minecraft:stone"));
        s.cmd(() -> tpLook(0f, 0f));
        s.run("QP select slot 1 (wall)", () -> H.p().getInventory().selected = 0);
        s.waitTicks(5);
        s.run("QP request throw (blocked)", () -> {
            before[0] = H.count("minecraft:ender_pearl");
            sawPearl[0] = sawSelect[0] = sawLease[0] = false;
            requested[0] = H.<QuickPearl>module(QuickPearl.ID).requestThrow();
        });
        s.waitUpTo("QP blocked notice", 30, () -> {
            sawPearl[0] |= pearlNear() != null;
            sawSelect[0] |= H.p().getInventory().selected != 0;
            sawLease[0] |= holds(ControlChannel.HOTBAR, QuickPearl.ID);
            return ToolsExtraScenario.noticeSeen("Pearl blocked");
        });
        s.waitTicks(10);
        s.run("QP blocked check", () -> {
            boolean notice = ToolsExtraScenario.noticeSeen("Pearl blocked");
            s.check("Quick Pearl holds a throw that would hit a block at the eyes (nothing selected or thrown)",
                    requested[0] && notice && !sawPearl[0] && !sawSelect[0] && !sawLease[0]
                            && H.count("minecraft:ender_pearl") == before[0] && !pearlCooldown(),
                    "requested " + requested[0] + ", notice " + notice + " (" + ToolsExtraScenario.notices()
                            + "), pearl seen " + sawPearl[0] + ", selection moved " + sawSelect[0] + ", lease "
                            + sawLease[0] + ", pearls " + before[0] + " -> " + H.count("minecraft:ender_pearl"));
        });
        s.always("QP cleanup", () -> {
            ToolsExtraScenario.unset(SmartInventory.ID, SmartInventory.K_REFILL_PEARLS);
            Harness.command(Arena.fill(-1, 1, 1, 1, 3, 1, "minecraft:air"));
            Harness.command("kill @e[type=minecraft:ender_pearl]");
            Harness.command("clear @s");
            if (H.p() != null) {
                H.p().getInventory().selected = 0;
            }
        });
        s.cmd(() -> tpLook(0f, 0f));
    }

    // ---- Local Rules ------------------------------------------------------------------------------------------------

    private static final String LIGHT = "light_overlay";
    private static final String BLAST = "blast_overlay";
    private static final String SPAWN_GEO = "spawn_geometry";

    private static Rule itemRule(String id, boolean enabled, String item, Rule.Action action, boolean whileJob) {
        return new Rule(id, enabled, new Rule.Condition(Rule.ConditionType.ITEM_COUNT, Rule.Comparison.AT_LEAST, 1,
                item), action, 0, whileJob);
    }

    private static Rule.Action toggleOn(String moduleId) {
        return new Rule.Action(Rule.ActionType.TOGGLE_MODULE, moduleId, true);
    }

    private static boolean on(String id) {
        IModule m = H.module(id);
        return m.isEnabled();
    }

    private static void localRules(Scenario s) {
        boolean[] hadRules = new boolean[1];
        List<String> added = new ArrayList<>();
        String[] ids = new String[4]; // main, disabled, job-gated, pearl
        int[] pearls = new int[1];
        boolean[] sawPearl = new boolean[1];

        s.cmd("clear @s");
        s.run("LR add rules (automation stopped)", () -> {
            LocalRules lr = H.module(LocalRules.ID);
            hadRules[0] = Services.modState().profile().module(LocalRules.ID).has(LocalRules.K_RULES);
            H.enable(LIGHT, false);
            H.enable(BLAST, false);
            H.enable(SPAWN_GEO, false);
            Services.arbiter().stopAll("playtest: local rules gate");
            ids[0] = lr.newRuleId();
            ids[1] = lr.newRuleId();
            ids[2] = lr.newRuleId();
            lr.addRule(itemRule(ids[0], true, "minecraft:diamond", toggleOn(LIGHT), false));
            lr.addRule(itemRule(ids[1], false, "minecraft:diamond", toggleOn(BLAST), false));
            lr.addRule(itemRule(ids[2], true, "minecraft:diamond", toggleOn(SPAWN_GEO), true));
            added.add(ids[0]);
            added.add(ids[1]);
            added.add(ids[2]);
        });
        s.cmd("give @s minecraft:diamond 1");
        s.waitTicks(50);
        s.run("LR Stop-All gate check", () -> {
            s.check("Local Rules evaluate nothing while automation is stopped",
                    !on(LIGHT) && H.count("minecraft:diamond") == 1,
                    "light_overlay " + on(LIGHT) + ", diamonds " + H.count("minecraft:diamond") + ", stopped "
                            + Services.arbiter().isAutomationStopped());
            Services.arbiter().resumeAutomation();
        });
        s.waitUpTo("LR rule fires after resume", 60, () -> on(LIGHT));
        s.waitTicks(25); // two more evaluation passes for the gated rules
        s.run("LR fire + gates check", () -> {
            LocalRules lr = H.module(LocalRules.ID);
            s.check("Local Rules fire an enabled rule when its condition turns true (switch module on)",
                    on(LIGHT) && lr.lastMessage().contains("switch " + LIGHT + " on"),
                    "light_overlay " + on(LIGHT) + ", last message '" + lr.lastMessage() + "'");
            s.check("a disabled rule and a while-a-job-runs rule (no job active) do not fire",
                    !on(BLAST) && !on(SPAWN_GEO),
                    "blast_overlay (disabled rule) " + on(BLAST) + ", spawn_geometry (job gate) " + on(SPAWN_GEO)
                            + ", job status " + lr.jobStatus());
            H.enable(LIGHT, false); // the user turns it off again; the condition stays true
        });
        s.waitTicks(50);
        s.run("LR rising edge check", () -> s.check("a rule does not fire again while its condition stays true",
                !on(LIGHT), "light_overlay " + on(LIGHT) + " after 50 ticks with the diamond still held"));
        s.cmd("clear @s minecraft:diamond");
        s.waitTicks(30);
        s.cmd("give @s minecraft:diamond 1");
        s.waitUpTo("LR rule re-arms", 60, () -> on(LIGHT));
        s.run("LR re-arm check", () -> s.check("the rule fires again after its condition went false and true",
                on(LIGHT), "light_overlay " + on(LIGHT)));

        // the pearl-escape action reaches Quick Pearl and really throws
        s.run("LR remove toggle rules, add pearl rule", () -> {
            LocalRules lr = H.module(LocalRules.ID);
            for (String id : added) {
                lr.removeRule(id);
            }
            added.clear();
            H.enable(LIGHT, false);
            ids[3] = lr.newRuleId();
            lr.addRule(itemRule(ids[3], true, "minecraft:emerald",
                    new Rule.Action(Rule.ActionType.QUICK_PEARL, "", false), false));
            added.add(ids[3]);
        });
        s.cmd("clear @s");
        s.cmd("item replace entity @s hotbar.0 with minecraft:stick 1");
        s.cmd("item replace entity @s hotbar.4 with minecraft:ender_pearl 2");
        s.cmd(() -> tpLook(0f, -35f));
        s.run("LR select slot 1", () -> {
            H.p().getInventory().selected = 0;
            pearls[0] = 2;
            sawPearl[0] = false;
        });
        s.waitTicks(5);
        s.cmd("give @s minecraft:emerald 1");
        s.waitUpTo("LR pearl escape throws", 80, () -> {
            sawPearl[0] |= pearlNear() != null;
            return sawPearl[0] && H.count("minecraft:ender_pearl") == pearls[0] - 1 && quickPearlIdle();
        });
        s.run("LR pearl escape check", () -> {
            LocalRules lr = H.module(LocalRules.ID);
            s.check("a pearl-escape rule makes Quick Pearl throw (and the hotbar is restored)",
                    sawPearl[0] && H.count("minecraft:ender_pearl") == pearls[0] - 1
                            && H.p().getInventory().selected == 0 && lr.lastMessage().contains("pearl escape"),
                    "pearl seen " + sawPearl[0] + ", pearls " + pearls[0] + " -> " + H.count("minecraft:ender_pearl")
                            + ", selected " + (H.p().getInventory().selected + 1) + ", last message '"
                            + lr.lastMessage() + "'");
        });
        s.always("LR cleanup", () -> {
            if (Services.arbiter().isAutomationStopped()) {
                Services.arbiter().resumeAutomation();
            }
            LocalRules lr = H.module(LocalRules.ID);
            for (String id : added) {
                lr.removeRule(id);
            }
            if (!hadRules[0]) {
                Services.modState().profile().module(LocalRules.ID).remove(LocalRules.K_RULES);
                lr.onProfileLoaded(Services.modState().profile());
            }
            H.enable(LIGHT, false);
            H.enable(BLAST, false);
            H.enable(SPAWN_GEO, false);
            Harness.command("kill @e[type=minecraft:ender_pearl]");
            Harness.command("clear @s");
        });
        s.cmd(() -> tpLook(0f, 0f));
        s.waitUpTo("LR cooldown over", 60, () -> !pearlCooldown());
    }

    // ---- Auto-Reconnect ---------------------------------------------------------------------------------------------

    private static boolean hasCancelButton(net.minecraft.client.gui.screens.Screen sc) {
        return sc != null && H.buttonByText(sc, "Cancel auto-reconnect") != null;
    }

    private static int countdown(AutoReconnect ar) {
        return (Integer) ToolsExtraScenario.field(ar, "countdown");
    }

    private static void autoReconnect(Scenario s) {
        int[] start = new int[1];
        s.run("AR enable, single player state", () -> {
            AutoReconnect ar = H.module(AutoReconnect.ID);
            H.setting(AutoReconnect.ID, AutoReconnect.K_DELAY, 30); // far from firing while the test looks
            H.enable(AutoReconnect.ID, true);
            Object last = ToolsExtraScenario.field(ar, "lastServer");
            s.check("Auto-Reconnect remembers no server in single player", last == null, "lastServer=" + last);
            H.mc().setScreen(new DisconnectedScreen(null, Component.literal("Connection Lost"),
                    Component.literal("Timed out")));
        });
        s.waitTicks(3);
        s.run("AR single player: no countdown", () -> {
            AutoReconnect ar = H.module(AutoReconnect.ID);
            s.check("a disconnect screen in single player starts no countdown and adds no button",
                    countdown(ar) < 0 && !hasCancelButton(H.mc().screen),
                    "countdown " + countdown(ar) + ", cancel button " + hasCancelButton(H.mc().screen));
            H.mc().setScreen(null);
            // From here a remote server is simulated: what onLoggingIn records when joining a multiplayer server.
            ToolsExtraScenario.setField(ar, "lastServer",
                    new ServerData("playtest", "127.0.0.1:1", ServerData.Type.OTHER));
            ToolsExtraScenario.setField(ar, "manualDisconnect", false);
            ToolsExtraScenario.setField(ar, "attempts", 0);
            H.mc().setScreen(new DisconnectedScreen(null, Component.literal("Connection Lost"),
                    Component.literal("Timed out")));
        });
        s.waitTicks(2);
        s.run("AR countdown starts", () -> start[0] = countdown(H.module(AutoReconnect.ID)));
        s.waitTicks(20);
        s.run("AR countdown + cancel", () -> {
            AutoReconnect ar = H.module(AutoReconnect.ID);
            int now = countdown(ar);
            var sc = H.mc().screen;
            boolean button = hasCancelButton(sc);
            s.check("an unexpected drop shows a countdown (first delay) and a cancel button",
                    start[0] > 20 * 28 && start[0] <= 20 * 30 && now < start[0] && now > 0 && button
                            && sc instanceof DisconnectedScreen,
                    "countdown at open " + start[0] + " ticks (30 s setting), 20 ticks later " + now + ", button "
                            + button + ", screen " + (sc == null ? "-" : sc.getClass().getSimpleName()));
            Button b = sc == null ? null : H.buttonByText(sc, "Cancel auto-reconnect");
            if (b != null) {
                b.onPress();
            }
            s.check("the cancel button stops the countdown", countdown(ar) < 0 && b != null && !b.active,
                    "countdown " + countdown(ar) + ", button active " + (b != null && b.active));
            H.mc().setScreen(null);
            H.mc().setScreen(new DisconnectedScreen(null, Component.translatable("disconnect.lost"),
                    Component.translatable("multiplayer.disconnect.kicked")));
        });
        s.waitTicks(2);
        s.run("AR kick refused", () -> {
            AutoReconnect ar = H.module(AutoReconnect.ID);
            s.check("a kick message starts no reconnect", countdown(ar) < 0 && !hasCancelButton(H.mc().screen),
                    "countdown " + countdown(ar) + ", cancel button " + hasCancelButton(H.mc().screen));
            H.mc().setScreen(null);
            H.mc().setScreen(new DisconnectedScreen(null, Component.literal("Connection Lost"),
                    Component.literal("Timed out")));
        });
        s.waitTicks(2);
        s.run("AR leave screen", () -> {
            AutoReconnect ar = H.module(AutoReconnect.ID);
            start[0] = countdown(ar);
            H.mc().setScreen(null); // the user leaves the disconnect screen
        });
        s.waitTicks(2);
        s.run("AR leave check", () -> {
            AutoReconnect ar = H.module(AutoReconnect.ID);
            s.check("leaving the disconnect screen cancels a running countdown", start[0] > 0 && countdown(ar) < 0,
                    "countdown before leaving " + start[0] + ", after " + countdown(ar));
            ToolsExtraScenario.setField(ar, "manualDisconnect", true);
            H.mc().setScreen(new DisconnectedScreen(null, Component.literal("Connection Lost"),
                    Component.literal("Timed out")));
        });
        s.waitTicks(2);
        s.run("AR manual disconnect", () -> {
            AutoReconnect ar = H.module(AutoReconnect.ID);
            s.check("after a manual disconnect no reconnect starts", countdown(ar) < 0,
                    "countdown " + countdown(ar));
            s.notTested("Auto-Reconnect real reconnect + job resume",
                    "single player has no remote server to drop and rejoin; the countdown, cancel, reason and manual"
                            + " filters were driven on a synthetic disconnect screen (ReconnectPolicy and"
                            + " ReconnectSafety are unit tested)");
        });
        s.always("AR cleanup", () -> {
            AutoReconnect ar = H.module(AutoReconnect.ID);
            ar.cancel();
            ToolsExtraScenario.setField(ar, "lastServer", null);
            ToolsExtraScenario.setField(ar, "manualDisconnect", false);
            ToolsExtraScenario.setField(ar, "attempts", 0);
            if (H.mc().screen instanceof DisconnectedScreen) {
                H.mc().setScreen(null);
            }
            H.enable(AutoReconnect.ID, false);
            ToolsExtraScenario.unset(AutoReconnect.ID, AutoReconnect.K_DELAY);
        });
    }

    // ---- Latency Simulator ------------------------------------------------------------------------------------------

    private static boolean handlerInstalled() {
        var c = H.mc().getConnection();
        return c != null && c.getConnection().channel() != null
                && c.getConnection().channel().pipeline().get(LATENCY_HANDLER) != null;
    }

    /** Adds the steps of one round trip: a setblock and the time until the client sees the block. */
    private static void roundTrip(Scenario s, String label, String block, long[] outMs) {
        long[] t0 = new long[1];
        int bx = Arena.cx + 10;
        int by = Arena.FLOOR + 1;
        int bz = Arena.cz + 10;
        s.run("LS send " + label, () -> {
            outMs[0] = -1;
            t0[0] = System.nanoTime();
            Harness.command("setblock " + bx + " " + by + " " + bz + " " + block);
        });
        s.waitUpTo("LS see " + label, 100, () -> {
            if (H.blockId(bx, by, bz).equals(block)) {
                outMs[0] = (System.nanoTime() - t0[0]) / 1_000_000L;
                return true;
            }
            return false;
        });
    }

    private static void latency(Scenario s) {
        long[] base = new long[1];
        long[] slow = new long[1];
        long[] after = new long[1];
        boolean[] installed = new boolean[1];
        s.cmd(() -> "setblock " + (Arena.cx + 10) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 10) + " minecraft:air");
        roundTrip(s, "baseline", "minecraft:gold_block", base);
        s.run("LS enable 250 ms", () -> {
            H.setting(LatencySimulator.ID, LatencySimulator.K_LATENCY, 250);
            H.setting(LatencySimulator.ID, LatencySimulator.K_JITTER, 0);
            LatencySimulator ls = H.module(LatencySimulator.ID);
            s.check("Latency Simulator is allowed in single player", ls.refusal() == null,
                    "refusal=" + ls.refusal());
            H.enable(LatencySimulator.ID, true);
        });
        s.waitTicks(5);
        s.run("LS installed", () -> installed[0] = handlerInstalled());
        roundTrip(s, "delayed", "minecraft:iron_block", slow);
        s.run("LS delayed check", () -> {
            LatencySimulator ls = H.module(LatencySimulator.ID);
            s.check("with 250 ms each way a command's round trip takes about 500 ms longer",
                    installed[0] && base[0] >= 0 && slow[0] >= 0 && slow[0] - base[0] >= 400 && slow[0] < 2500,
                    "handler in pipeline " + installed[0] + ", baseline " + base[0] + " ms, with simulator "
                            + slow[0] + " ms, status '" + ls.status() + "'");
            H.enable(LatencySimulator.ID, false);
        });
        s.waitUpTo("LS handler removed", 60, () -> !handlerInstalled());
        roundTrip(s, "after", "minecraft:gold_block", after);
        s.run("LS off check", () -> {
            LatencySimulator ls = H.module(LatencySimulator.ID);
            s.check("switching it off removes the handler and the round trip is back to normal",
                    !handlerInstalled() && !ls.isEnabled() && after[0] >= 0 && after[0] < base[0] + 300,
                    "handler " + handlerInstalled() + ", enabled " + ls.isEnabled() + ", round trip " + after[0]
                            + " ms (baseline " + base[0] + " ms)");
        });
        s.always("LS cleanup", () -> {
            H.enable(LatencySimulator.ID, false);
            ToolsExtraScenario.unset(LatencySimulator.ID, LatencySimulator.K_LATENCY);
            ToolsExtraScenario.unset(LatencySimulator.ID, LatencySimulator.K_JITTER);
            Harness.command("setblock " + (Arena.cx + 10) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 10)
                    + " minecraft:air");
        });
    }
}
