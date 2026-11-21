package com.utilitysuite.tools.commands;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IModuleRegistry;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat dot-commands typed by the user in their own chat box. A line that starts with the prefix is cancelled before it
 * is sent (it never reaches the server, typos included) and answered with a local chat line. Single-user: there is no
 * remote or third-party command path; only {@link ClientChatEvent} (the local player's own outgoing chat) is read.
 */
public final class DotCommands implements IModule, IConfigurable {

    public static final String ID = "dot_commands";
    public static final String K_PREFIX = "prefix";

    private boolean enabled = true;
    private String prefix = DotCommandParser.DEFAULT_PREFIX;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TOOLS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void onTick() {
        // event-driven only
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        prefix = DotCommandParser.normalizePrefix(profile.getString(ID, K_PREFIX, DotCommandParser.DEFAULT_PREFIX));
    }

    @Override
    public String displayName() {
        return "Dot-Commands";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.text(K_PREFIX, "Prefix", DotCommandParser.DEFAULT_PREFIX, "",
                "Chat lines starting with this are handled locally and never sent. " + DotCommandParser.helpText(".")));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }

    /** Game bus, highest priority so the line is cancelled before anything else sees it. */
    public void onClientChat(ClientChatEvent event) {
        if (!enabled) {
            return;
        }
        DotCommandParser.Command cmd = DotCommandParser.parse(event.getMessage(), prefix);
        if (cmd == null) {
            return;
        }
        event.setCanceled(true);
        try {
            execute(cmd);
        } catch (RuntimeException e) {
            reply("Command failed: " + e.getMessage(), true);
        }
    }

    private void execute(DotCommandParser.Command cmd) {
        switch (cmd.kind()) {
            case EMPTY, HELP -> reply(DotCommandParser.helpText(prefix), false);
            case LIST -> list();
            case STOP -> {
                Services.arbiter().stopAll("dot-command");
                reply("Stop-All: every automation stopped.", false);
            }
            case TOGGLE -> toggle(cmd.arg());
            case GOAL -> goal(cmd.arg());
            case UNKNOWN -> reply("Unknown command '" + cmd.name() + "' (not sent). " + DotCommandParser.helpText(prefix),
                    true);
        }
    }

    private void list() {
        IModuleRegistry reg = Services.modules();
        StringBuilder b = new StringBuilder("Modules: ");
        boolean first = true;
        for (IModule m : reg.all()) {
            if (!first) {
                b.append(", ");
            }
            first = false;
            b.append(m.id()).append(m.isEnabled() ? " [on]" : "");
        }
        reply(b.toString(), false);
    }

    private void toggle(String query) {
        if (query.isEmpty()) {
            reply("Usage: " + prefix + "t <module id or name>", true);
            return;
        }
        List<IModule> all = Services.modules().all();
        List<String> ids = new ArrayList<>(all.size());
        List<String> names = new ArrayList<>(all.size());
        for (IModule m : all) {
            ids.add(m.id());
            names.add(m instanceof IConfigurable c ? c.displayName() : m.id());
        }
        int i = DotCommandParser.matchModule(query, ids, names);
        if (i < 0) {
            reply("No single module matches '" + query + "'. " + prefix + "list shows them.", true);
            return;
        }
        IModule m = all.get(i);
        boolean on = !m.isEnabled();
        m.setEnabled(on);
        if (Services.modState().profile() != null) {
            // read back: a module may refuse to turn on (e.g. not allowed here)
            Services.modState().profile().set(m.id(), "enabled", m.isEnabled());
            Services.modState().markDirty();
        }
        reply(names.get(i) + (m.isEnabled() ? " on" : " off") + (on != m.isEnabled() ? " (refused)" : ""), false);
    }

    /** Same path as the menu's goal box: parse, then submit. */
    private void goal(String text) {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            reply("The goal engine is not loaded.", true);
            return;
        }
        if (text.isBlank()) {
            reply("Usage: " + prefix + "goal <text>, e.g. " + prefix + "goal gather 32 iron_ingot", true);
            return;
        }
        ParseResult r = goals.parse(text.trim());
        if (!r.isOk()) {
            reply(r.error() + (r.suggestions().isEmpty() ? "" : "  Try: " + String.join(", ", r.suggestions())), true);
            return;
        }
        long id = goals.submit(r.request());
        reply("Queued job #" + id + ": " + r.request().label(), false);
    }

    private static void reply(String text, boolean error) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.player.displayClientMessage(Component.literal("[Suite] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(text).withStyle(error ? ChatFormatting.RED : ChatFormatting.GRAY)), false);
    }
}
