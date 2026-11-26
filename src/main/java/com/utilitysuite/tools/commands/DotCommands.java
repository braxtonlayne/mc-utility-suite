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
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.world.seed.SeedScannerModule;
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
            notice("Command failed: " + e.getMessage(), NoticeKind.ERROR);
        }
    }

    private void execute(DotCommandParser.Command cmd) {
        switch (cmd.kind()) {
            case EMPTY, HELP -> reply(DotCommandParser.helpText(prefix), false);
            case LIST -> list();
            case STOP -> {
                Services.arbiter().stopAll("dot-command");
                notice("Stop-All: every automation stopped.", NoticeKind.WARN);
            }
            case TOGGLE -> toggle(cmd.arg());
            case GOAL -> goal(cmd.arg());
            case UNKNOWN -> {
                if ("seed".equals(cmd.name())) {
                    seed(cmd.arg());
                    return;
                }
                if ("jumpcap".equals(cmd.name())) {
                    java.nio.file.Path file = JumpCapture.capture(cmd.arg());
                    reply(file == null ? "No world to capture" : "Jump captured: " + file.getFileName()
                            + " (in utilitysuite/jumpcap)", file == null);
                    return;
                }
                notice("Unknown command '" + cmd.name() + "' (not sent)", NoticeKind.ERROR);
                reply(DotCommandParser.helpText(prefix), false); // the list of commands is output the user reads
            }
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
            notice("Usage: " + prefix + "t <module id or name>", NoticeKind.ERROR);
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
            notice("No single module matches '" + query + "'. " + prefix + "list shows them.", NoticeKind.ERROR);
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
        boolean refused = on != m.isEnabled();
        notice(names.get(i) + (m.isEnabled() ? " on" : " off") + (refused ? " (refused)" : ""),
                refused ? NoticeKind.WARN : NoticeKind.INFO);
    }

    /** Same path as the menu's goal box: parse, then submit. */
    private void goal(String text) {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            notice("The goal engine is not loaded.", NoticeKind.ERROR);
            return;
        }
        if (text.isBlank()) {
            notice("Usage: " + prefix + "goal <text>, e.g. " + prefix + "goal gather 32 iron_ingot", NoticeKind.ERROR);
            return;
        }
        ParseResult r = goals.parse(text.trim());
        if (!r.isOk()) {
            Services.visuals().notices().post(NoticeKind.ERROR, r.error(),
                    r.suggestions().isEmpty() ? null : "Try: " + String.join(", ", r.suggestions()));
            return;
        }
        long id = goals.submit(r.request());
        notice("Queued job #" + id + ": " + r.request().label(), NoticeKind.DONE);
    }

    private void seed(String arg) {
        SeedScannerModule scanner = Services.modules().find(SeedScannerModule.class).orElse(null);
        if (scanner == null) {
            notice("Seed scanner module is not loaded.", NoticeKind.ERROR);
            return;
        }
        String trimmed = arg == null ? "" : arg.trim();
        String[] args = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        reply(scanner.handleDotCommand(args), false);
    }

    /** Command feedback (what the command did) as a toast; lists and help stay in chat through {@link #reply}. */
    private static void notice(String title, NoticeKind kind) {
        Services.visuals().notices().post(kind, title, null);
    }

    private static void reply(String text, boolean error) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.player.sendSystemMessage(Component.literal("[Suite] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(text).withStyle(error ? ChatFormatting.RED : ChatFormatting.GRAY)));
    }
}
