package com.utilitysuite.ui.tabs;

import com.utilitysuite.combat.CombatBootstrap;
import com.utilitysuite.combat.allies.AllyEntry;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.allies.AllyRosterModule;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.SettingsAccess;
import com.utilitysuite.ui.SettingsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Combat tab: one prominent Auto-Fight master toggle (the CombatAssistant's enabled flag) with its basic settings and a
 * collapsed Advanced drawer, the Ally Roster table (Never Target / Defend / Remove; add an online player by name), the
 * Fight Coach toggle, then any other COMBAT module.
 */
public final class CombatTab {

    private static final String ASSISTANT = "combat_assistant";
    private static final String COACH = "fight_coach";
    private static final String K_ALLY = "combat.allyName";

    private CombatTab() {
    }

    public static String signature() {
        AllyRosterModule allies = CombatBootstrap.allies();
        StringBuilder sb = new StringBuilder();
        if (allies != null) {
            for (AllyEntry e : allies.roster().entries()) {
                sb.append(e.uuid()).append(e.neverTarget()).append(e.defend()).append(';');
            }
        }
        Services.modules().byId(ASSISTANT).ifPresent(m -> sb.append(m.isEnabled()));
        Services.modules().byId(COACH).ifPresent(m -> sb.append(m.isEnabled()));
        return sb.toString();
    }

    public static void build(Panel p) {
        Optional<IModule> assistant = Services.modules().byId(ASSISTANT);
        p.heading("Auto-Fight");
        if (assistant.isEmpty()) {
            p.text("The combat assistant is not installed.", Panel.GRAY);
        } else {
            IModule a = assistant.get();
            boolean on = a.isEnabled();
            p.button(on ? "Auto-Fight: ON" : "Auto-Fight: OFF",
                    "Defensive combat assistance: engages only after you attack or are attacked", 0, p.width(), b -> {
                        CoreBootstrap.modules().setModuleEnabled(ASSISTANT, !a.isEnabled(),
                                Services.modState().profile());
                        Services.modState().markDirty();
                        p.rebuild();
                    }).setMessage(Component.literal(on ? "Auto-Fight: ON" : "Auto-Fight: OFF")
                    .withStyle(on ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.WHITE));
            p.nextRow();
            if (a instanceof IConfigurable c) {
                SettingsRenderer.settings(p, ASSISTANT, c.settings(), new SettingsAccess(Services.modState().profile(),
                        ASSISTANT, c::onSettingChanged, Services.modState()::markDirty));
            }
        }
        allyRoster(p);
        Services.modules().byId(COACH).ifPresent(m -> SettingsRenderer.module(p, m));
        for (IModule m : Services.modules().byCategory(ModuleCategory.COMBAT)) {
            String id = m.id();
            if (!id.equals(ASSISTANT) && !id.equals(COACH) && !id.equals(AllyRosterModule.ID)) {
                SettingsRenderer.module(p, m);
            }
        }
    }

    private static void allyRoster(Panel p) {
        p.heading("Ally roster");
        AllyRosterModule mod = CombatBootstrap.allies();
        if (mod == null) {
            p.text("The ally roster is not available.", Panel.GRAY);
            return;
        }
        AllyRoster roster = mod.roster();
        int w = p.width();
        List<AllyEntry> entries = roster.entries();
        if (entries.isEmpty()) {
            p.text("No allies yet. Add an online player by name, or middle-click a player in game.", Panel.GRAY);
        } else {
            p.textIndented("Name", Panel.GRAY, 4);
        }
        int bw = 86;
        for (AllyEntry e : entries) {
            p.rowLabel(e.name() + (e.global() ? " (all worlds)" : ""), 4, w - 3 * (bw + 4) - 8, Panel.WHITE);
            p.toggle("Never target", e.neverTarget(), w - 3 * (bw + 4) + 4, bw, v -> roster.setNeverTarget(e.uuid(), v))
                    .setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.literal("Never attack this player, even if they hit you")));
            p.toggle("Defend", e.defend(), w - 2 * (bw + 4) + 4, bw, v -> roster.setDefend(e.uuid(), v));
            p.button("Remove", w - bw, bw, b -> {
                roster.remove(e.uuid());
                p.rebuild();
            });
            p.nextRow();
        }
        p.textField(K_ALLY, "Online player name", 0, w - 90);
        p.button("Add ally", w - 86, 86, b -> {
            String name = p.state(K_ALLY, "").trim();
            PlayerInfo info = findOnline(name);
            if (info == null) {
                p.toast(name.isEmpty() ? "Type a player name" : "No online player named '" + name + "'");
                return;
            }
            roster.add(info.getProfile().getId(), info.getProfile().getName(), false);
            p.setState(K_ALLY, "");
            p.toast("Ally added: " + info.getProfile().getName());
            p.rebuild();
        });
        p.nextRow();
        SettingsRenderer.settings(p, AllyRosterModule.ID, mod.settings(), new SettingsAccess(
                Services.modState().profile(), AllyRosterModule.ID, mod::onSettingChanged,
                Services.modState()::markDirty));
    }

    private static PlayerInfo findOnline(String name) {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        if (c == null || name.isEmpty()) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (PlayerInfo info : c.getOnlinePlayers()) {
            if (info.getProfile().getName().toLowerCase(Locale.ROOT).equals(lower)) {
                return info;
            }
        }
        return null;
    }
}
