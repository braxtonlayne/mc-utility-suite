package com.utilitysuite.devtest;

import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.item.Items;

/**
 * Scenario 11: the modules added by the 2026-11-21 parallel run. Dot-commands (handled locally, never sent), the
 * Offhand Manager's totem-at-low-health rule, Auto-Respawn, and a smoke pass that turns every new render/HUD module on
 * for a moment and checks the client keeps ticking with them on.
 */
final class ModulesScenario {

    private static final String[] SMOKE = {"light_overlay", "blast_overlay", "spawn_geometry", "freelook",
            "render_filters", "target_hud", "tab_gui", "trigger_assist", "move_toggles", "mount_jump", "elytra_swap",
            "auto_torch", "lava_plug", "auto_fish"};

    private ModulesScenario() {
    }

    private static IModule module(String id) {
        return Services.modules().byId(id).orElse(null);
    }

    static Scenario build() {
        Scenario s = new Scenario("11-modules", "New modules: dot-commands, offhand, respawn, smoke");
        Arena.ensure(s);
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("clear @s");
        s.cmd("effect clear @s"); // earlier scenarios leave Resistance on, which would block the damage step
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        dotCommands(s);
        parkourKeys(s);
        offhand(s);
        respawn(s);
        smoke(s);
        s.always("disarm", () -> {
            H.enable("offhand_manager", false);
            H.enable("auto_respawn", false);
            for (String id : SMOKE) {
                H.enable(id, false);
            }
            Harness.command("effect give @s minecraft:instant_health 1 5 true");
        });
        return s;
    }

    private static void dotCommands(Scenario s) {
        boolean[] before = new boolean[1];
        String[] out = new String[1];
        s.run("dot-command", () -> {
            H.enable("dot_commands", true);
            IModule m = module("light_overlay");
            before[0] = m != null && m.isEnabled();
            ChatCapture.drain();
            out[0] = net.neoforged.neoforge.client.ClientHooks.onClientSendMessage(".t light_overlay");
        });
        s.waitTicks(10);
        s.run("dot-command result", () -> {
            IModule m = module("light_overlay");
            boolean now = m != null && m.isEnabled();
            s.check("'.t light_overlay' toggles the module locally and is not sent to the server",
                    now != before[0] && (out[0] == null || out[0].isEmpty()),
                    "enabled " + before[0] + " -> " + now + ", message after the event '" + out[0] + "', chat "
                            + ChatCapture.drain());
            H.enable("light_overlay", false);
        });
        // .jumpcap: the blocks around the player, with collision boxes, land in a file a test can be built from
        java.nio.file.Path dir = H.mc().gameDirectory.toPath().resolve("utilitysuite").resolve("jumpcap");
        long[] since = new long[1];
        s.cmd("setblock ~2 ~ ~ minecraft:oak_slab");
        s.waitTicks(5);
        s.run("jumpcap", () -> {
            since[0] = System.currentTimeMillis();
            out[0] = net.neoforged.neoforge.client.ClientHooks.onClientSendMessage(".jumpcap playtest");
        });
        s.waitTicks(20);
        s.run("jumpcap result", () -> {
            String text = "";
            try (var files = java.nio.file.Files.list(dir)) {
                java.nio.file.Path newest = files.filter(f -> f.toFile().lastModified() >= since[0] - 1000)
                        .max(java.util.Comparator.comparingLong(f -> f.toFile().lastModified())).orElse(null);
                text = newest == null ? "" : java.nio.file.Files.readString(newest);
            } catch (java.io.IOException e) {
                text = "io: " + e;
            }
            s.check("'.jumpcap' saves the nearby blocks with collision boxes and is not sent",
                    (out[0] == null || out[0].isEmpty()) && text.contains("\"note\": \"playtest\"")
                            && text.contains("minecraft:oak_slab[type=bottom") && text.contains("0.50000")
                            && text.contains("\"assist\": ") && text.contains("\"cells\": [["),
                    "message after the event '" + out[0] + "', file " + text.length() + " chars"
                            + (text.length() < 400 ? ": " + text : ""));
        });
        s.cmd("setblock ~2 ~ ~ minecraft:air");
    }

    /** The parkour [ and ] keys (real key presses): pin the looked-at block; mark its type for Parkour blocks. */
    private static void parkourKeys(Scenario s) {
        String ma = com.utilitysuite.travel.movement.MovementAssist.ID;
        String allowedOnly = "parkour_blocks_allowed_only";
        String allowed = "parkour_blocks_allowed";
        s.cmd(() -> "tp @s " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 0.5) + " 0 90");
        s.waitTicks(5);
        String[] chat = new String[3];
        s.run("] with the setting off", () -> {
            H.setting(ma, allowed, "");
            H.setting(ma, allowedOnly, false);
            ChatCapture.drain();
            H.physicalKey(com.mojang.blaze3d.platform.InputConstants.KEY_RBRACKET, true);
            H.physicalKey(com.mojang.blaze3d.platform.InputConstants.KEY_RBRACKET, false);
        });
        s.waitTicks(3);
        s.run("] with the setting on", () -> {
            chat[0] = String.valueOf(ChatCapture.drain());
            H.setting(ma, allowedOnly, true);
            H.physicalKey(com.mojang.blaze3d.platform.InputConstants.KEY_RBRACKET, true);
            H.physicalKey(com.mojang.blaze3d.platform.InputConstants.KEY_RBRACKET, false);
        });
        s.waitTicks(3);
        s.run("[ pins the looked-at block", () -> {
            chat[1] = String.valueOf(ChatCapture.drain());
            H.physicalKey(com.mojang.blaze3d.platform.InputConstants.KEY_LBRACKET, true);
            H.physicalKey(com.mojang.blaze3d.platform.InputConstants.KEY_LBRACKET, false);
        });
        s.waitTicks(3);
        s.run("parkour keys result", () -> {
            chat[2] = String.valueOf(ChatCapture.drain());
            String list = Services.modState().profile().getString(ma, allowed, "");
            net.minecraft.core.BlockPos below = H.p().blockPosition().below();
            String floor = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(H.mc().level.getBlockState(below).getBlock()).toString();
            s.check("] marks the looked-at block type only with 'Place only on allowed blocks' on; [ pins a target",
                    chat[0].contains("Turn on") && list.equals(floor) && chat[1].contains("may place on")
                            && chat[2].contains("Jump target pinned"),
                    "off: " + chat[0] + "; on: " + chat[1] + "; list '" + list + "' floor " + floor + "; pin: " + chat[2]);
            H.setting(ma, allowedOnly, false);
            H.setting(ma, allowed, "");
            H.physicalKey(com.mojang.blaze3d.platform.InputConstants.KEY_LBRACKET, true); // (on the same block: clears)
            H.physicalKey(com.mojang.blaze3d.platform.InputConstants.KEY_LBRACKET, false);
        });
        s.waitTicks(3);
    }

    private static void offhand(Scenario s) {
        s.cmd("give @s minecraft:totem_of_undying");
        s.run("offhand on", () -> H.enable("offhand_manager", true));
        s.cmd("effect give @s minecraft:instant_damage 1 1 true");
        s.waitUpTo("totem in the offhand", 20 * 5, () -> H.p().getOffhandItem().is(Items.TOTEM_OF_UNDYING));
        s.run("offhand result", () -> s.check("Offhand Manager moves a totem to the offhand at low health",
                H.p().getOffhandItem().is(Items.TOTEM_OF_UNDYING),
                Harness.fmt("health %.1f, offhand %s", H.p().getHealth(), H.p().getOffhandItem())
                        + "; " + H.fields(module("offhand_manager"), "planned", "plannedDelay", "lease", "actingForAutopilot")
                        + "; autopilot on " + (module("autopilot") != null && module("autopilot").isEnabled())
                        + ", stopped " + Services.arbiter().isAutomationStopped()));
        s.cmd("effect give @s minecraft:instant_health 1 5 true");
        s.cmd("clear @s");
        s.run("offhand off", () -> H.enable("offhand_manager", false));
    }

    private static void respawn(Scenario s) {
        boolean[] sawDeath = new boolean[1];
        s.run("respawn on", () -> {
            H.enable("auto_respawn", true);
            s.deathExpected = true;
        });
        s.cmd("kill @s");
        s.waitUpTo("death screen", 60, () -> {
            sawDeath[0] |= H.mc().gui.screen() instanceof DeathScreen;
            return sawDeath[0];
        });
        s.waitUpTo("respawned", 20 * 6, () -> H.p() != null && H.p().isAlive()
                && !(H.mc().gui.screen() instanceof DeathScreen));
        s.run("respawn result", () -> s.deathExpected = false);
        s.run("respawn check", () -> s.check("Auto-Respawn presses respawn after the death screen",
                sawDeath[0] && H.p().isAlive() && !(H.mc().gui.screen() instanceof DeathScreen),
                "saw death screen " + sawDeath[0] + " (harness respawn off), alive " + H.p().isAlive() + ", screen " + H.mc().gui.screen()));
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
    }

    private static void smoke(Scenario s) {
        int[] ticks = new int[1];
        s.run("smoke on", () -> {
            for (String id : SMOKE) {
                H.enable(id, true);
            }
        });
        s.cmd(() -> "summon minecraft:creeper " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 6.5)
                + " {NoAI:1b,PersistenceRequired:1b,ExplosionRadius:0b}");
        s.waitUpTo("40 ticks with everything on", 60, () -> ++ticks[0] >= 40);
        Shots.add(s, "modules-smoke");
        s.run("smoke result", () -> {
            StringBuilder off = new StringBuilder();
            for (String id : SMOKE) {
                IModule m = module(id);
                if (m == null || !m.isEnabled()) {
                    off.append(id).append(' ');
                }
            }
            s.check("every new module ticks and renders for 2 s without dropping out",
                    ticks[0] >= 40 && off.length() == 0, "ticks " + ticks[0] + (off.length() == 0 ? ""
                            : ", missing or off: " + off));
        });
        s.cmd("kill @e[type=minecraft:creeper]");
    }
}
