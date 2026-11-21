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
import com.utilitysuite.ui.kit.CardNode;
import com.utilitysuite.ui.kit.ChipNode;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.ToggleSwitch;
import com.utilitysuite.ui.kit.UiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Combat tab: a prominent Auto-Fight hero card (the CombatAssistant's enabled flag as the master switch, an Armed/Off
 * chip, its basic settings and a collapsed Advanced section), the Ally Roster (Never target / Defend switches and
 * Remove per player; add an online player by name), the Fight Coach card, then any other COMBAT module.
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
        if (assistant.isEmpty()) {
            p.card("Auto-Fight", "not installed", 0, CardNode.Tone.HERO);
            p.paragraph("The combat assistant is not installed.", Panel.GRAY);
        } else {
            IModule a = assistant.get();
            boolean on = a.isEnabled();
            ChipNode chip = on ? new ChipNode(p.font(), "Armed", UiTheme.RUNNING) : null; // the switch already says off
            p.card("Auto-Fight", "Defensive combat assistance", ToggleSwitch.W + (chip == null ? 8 : chip.w + 18),
                    CardNode.Tone.HERO);
            p.cardToggle(on, "module." + ASSISTANT, v -> {
                CoreBootstrap.modules().setModuleEnabled(ASSISTANT, v, Services.modState().profile());
                Services.modState().markDirty();
                p.rebuild();
            }).tooltip("Defensive combat assistance: engages only after you attack or are attacked");
            if (chip != null) {
                p.headerNode(chip, ToggleSwitch.W + 10);
            }
            p.paragraph("Engages only after you attack or are attacked, never on its own. Your own keys and mouse "
                    + "always win; Stop-All ends it at once.", Panel.DIM);
            p.gap(2);
            int w = p.width();
            int bw = Math.min(QuickActions.BTN_W, Math.max(56, w / 4));
            p.rowLabel("Arm or retreat now", 0, w - 2 * bw - 16, Panel.WHITE)
                    .tooltip("Same as the Combat retreat key: ends the current engagement at once");
            p.button(on ? "Disarm" : "Arm", on ? "Switch Auto-Fight off" : "Switch Auto-Fight on", w - 2 * bw - 6, bw,
                    on ? PillButton.Style.SECONDARY : PillButton.Style.PRIMARY, b -> {
                        CoreBootstrap.modules().setModuleEnabled(ASSISTANT, !a.isEnabled(),
                                Services.modState().profile());
                        Services.modState().markDirty();
                        p.rebuild();
                    });
            p.button("Retreat", "Ends the current fight and backs off (Combat retreat key)", w - bw, bw,
                    PillButton.Style.DANGER_SOFT, b -> {
                        var ca = CombatBootstrap.assistant();
                        if (ca != null) {
                            ca.onCancelKey();
                            p.toast("Disengaged");
                        } else {
                            p.toast("Combat assistant is not available");
                        }
                    });
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
        AllyRosterModule mod = CombatBootstrap.allies();
        if (mod == null) {
            p.card("Ally roster", "not available");
            p.paragraph("The ally roster is not available.", Panel.GRAY);
            return;
        }
        AllyRoster roster = mod.roster();
        List<AllyEntry> entries = roster.entries();
        p.card("Ally roster", entries.isEmpty() ? "players you never target" : entries.size()
                + (entries.size() == 1 ? " ally" : " allies"));
        int w = p.width();
        int colW = 74;
        int removeW = 60;
        int col2 = w - removeW - 8 - colW;
        int col1 = col2 - colW;
        if (entries.isEmpty()) {
            p.paragraph("No allies yet. Add an online player by name, or middle-click a player in game.", Panel.GRAY);
        } else {
            p.rowLabel("Player", 4, col1 - 8, Panel.GRAY);
            p.rowLabel("Never target", col1, colW, Panel.GRAY);
            p.rowLabel("Defend", col2, colW, Panel.GRAY);
            p.nextRow();
        }
        for (AllyEntry e : entries) {
            p.listRow(null);
            p.rowLabel(e.name() + (e.global() ? "  (all worlds)" : ""), 4, col1 - 12, Panel.WHITE);
            p.place(new ToggleSwitch(e.neverTarget(), v -> roster.setNeverTarget(e.uuid(), v))
                    .animKey("ally.nt." + e.uuid()), col1 + 2)
                    .tooltip("Never attack this player, even if they hit you");
            p.place(new ToggleSwitch(e.defend(), v -> roster.setDefend(e.uuid(), v))
                    .animKey("ally.def." + e.uuid()), col2 + 2)
                    .tooltip("Help this player when something attacks them");
            p.button("Remove", null, w - removeW, removeW, PillButton.Style.DANGER_SOFT, b -> {
                roster.remove(e.uuid());
                p.rebuild();
            });
            p.nextRow();
        }
        p.gap(4);
        p.textField(K_ALLY, "Online player name", 0, w - 86);
        p.button("Add ally", null, w - 80, 80, PillButton.Style.PRIMARY, b -> {
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
