package com.utilitysuite.ui.tabs;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.config.ModState;
import com.utilitysuite.core.config.ProfileIds;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.tools.ToolsBootstrap;
import com.utilitysuite.tools.inventory.PinnedSlots;
import com.utilitysuite.tools.inventory.SmartInventory;
import com.utilitysuite.tools.packetlab.LatencySimulator;
import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.tools.rules.LocalRules;
import com.utilitysuite.tools.rules.Rule;
import com.utilitysuite.ui.ModulePanels;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.ProfileCopier;
import com.utilitysuite.ui.SettingWidgetMapper;
import com.utilitysuite.ui.SettingsAccess;
import com.utilitysuite.ui.SettingsRenderer;
import com.utilitysuite.ui.TimelineScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Tools tab: Smart Inventory (settings + pinned slots), Packet Lab (toggle, live rates, timeline viewer), the latency
 * simulator, Local Rules (list + builder), HUD settings, Profiles (active id, copy from global or another saved profile),
 * hotkeys, core settings and Resume automation.
 */
public final class ToolsTab {

    private static final String K_COND = "rules.cond";
    private static final String K_CMP = "rules.cmp";
    private static final String K_VALUE = "rules.value";
    private static final String K_COND_ARG = "rules.condArg";
    private static final String K_ACTION = "rules.action";
    private static final String K_ACTION_ARG = "rules.actionArg";
    private static final String K_FLAG = "rules.flag";
    private static final String K_COOLDOWN = "rules.cooldown";

    /** Core settings rendered with the generic renderer, stored in global.json under "core". */
    static final List<SettingSpec> CORE_SPECS = List.of(
            SettingSpec.integer(ModState.KEY_OVERRIDE_PAUSE_TICKS, "Manual override pause (ticks)", 20, 0, 200, "",
                    "After you touch the controls, automation waits this long before taking over again."),
            SettingSpec.decimal(ModState.KEY_MOUSE_THRESHOLD_PX, "Mouse override threshold (px)", 6.0, 0.5, 50.0, "",
                    "Mouse movement per tick that counts as you taking the camera."),
            SettingSpec.decimal(ModState.KEY_CAMERA_SPEED, "Camera turn speed (deg/s)", 240.0, 30.0, 1080.0, "",
                    "Base speed of automated camera turns."));

    private ToolsTab() {
    }

    public static String signature() {
        StringBuilder sb = new StringBuilder();
        LocalRules r = ToolsBootstrap.rules();
        if (r != null) {
            sb.append(r.rules().size()).append(r.depositRunning());
            for (Rule rule : r.rules()) {
                sb.append(rule.enabled() ? '1' : '0');
            }
        }
        SmartInventory inv = ToolsBootstrap.inventory();
        if (inv != null) {
            sb.append('/').append(inv.pinnedMask()).append(inv.isEnabled());
        }
        sb.append('/').append(Services.modState().profile().profileId());
        return sb.toString();
    }

    public static void build(Panel p) {
        if (Services.arbiter().isAutomationStopped()) {
            p.heading("Automation stopped");
            p.text("Stop-All is active: no module may take control until you resume.", Panel.RED);
            p.button("Resume automation", 0, 160, b -> {
                Services.arbiter().resumeAutomation();
                p.toast("Automation resumed");
                p.rebuild();
            });
            p.nextRow();
        }
        List<IModule> mods = Services.modules().byCategory(ModuleCategory.TOOLS);
        for (IModule m : mods) {
            SettingsRenderer.module(p, m);
        }
        Services.modules().byId(com.utilitysuite.ui.SuiteHud.ID).ifPresent(m -> SettingsRenderer.module(p, m));
        profiles(p);
        hotkeys(p);
        coreSettings(p);
    }

    // ---- custom module panels ---------------------------------------------------------------------------------------

    public static void registerPanels() {
        ModulePanels.register(SmartInventory.ID, (p, m) -> {
            if (!(m instanceof SmartInventory inv)) {
                return;
            }
            p.text("Pinned hotbar slots (never overwritten or dumped):", Panel.GRAY);
            int bw = Math.min(24, (p.width() - 8 * 2) / 9);
            for (int i = 0; i < 9; i++) {
                int slot = i;
                boolean pinned = PinnedSlots.isPinned(inv.pinnedMask(), i);
                Button b = p.button((pinned ? "■" : "") + (i + 1), i * (bw + 2), bw, btn -> {
                    inv.togglePinned(slot);
                    p.rebuild();
                });
                if (pinned) {
                    b.setMessage(Component.literal(String.valueOf(i + 1)).withStyle(net.minecraft.ChatFormatting.GOLD));
                }
            }
            p.nextRow();
            p.dynamic(() -> inv.lastResult().isEmpty() ? "" : "Last: " + inv.lastResult(), Panel.GRAY);
        });
        ModulePanels.register(PacketLab.ID, (p, m) -> {
            if (!(m instanceof PacketLab lab)) {
                return;
            }
            p.text("Read-only: records packet types, sizes and times only (no contents).", Panel.GRAY);
            p.button("Open timeline", 0, 120, b -> Minecraft.getInstance().setScreen(
                    new TimelineScreen(Minecraft.getInstance().screen, lab)));
            p.button("Clear", 124, 60, b -> {
                lab.clearTimeline();
                p.toast("Timeline cleared");
            });
            p.nextRow();
            p.dynamic(() -> {
                if (!lab.isEnabled()) {
                    return "Capture is off.";
                }
                long s = System.currentTimeMillis() / 1000L - 1;
                return "Last second: " + lab.packetRates().get(s, PacketLab.RATE_IN_COUNT) + " in / "
                        + lab.packetRates().get(s, PacketLab.RATE_OUT_COUNT) + " out packets, "
                        + lab.packetRates().get(s, PacketLab.RATE_IN_BYTES) / 1024 + " KiB in"
                        + (lab.bursts().bursts() > 0 ? "  |  repeated corrections: " + lab.bursts().bursts() : "");
            }, Panel.WHITE);
        });
        ModulePanels.register(LatencySimulator.ID, (p, m) -> {
            if (!(m instanceof LatencySimulator sim)) {
                return;
            }
            p.text("Test tool: delays your own packets. Single-player or allowed test servers only;", Panel.YELLOW);
            p.text("off by default and switched off automatically when you disconnect.", Panel.YELLOW);
            p.dynamic(() -> "Status: " + sim.status(), sim.isEnabled() ? Panel.GREEN : Panel.GRAY);
        });
        ModulePanels.register(LocalRules.ID, (p, m) -> {
            if (m instanceof LocalRules rules) {
                rulesPanel(p, rules);
            }
        });
    }

    private static void rulesPanel(Panel p, LocalRules rules) {
        int w = p.width();
        List<Rule> list = rules.rules();
        if (list.isEmpty()) {
            p.text("No rules yet. Example: If inventory >= 90% full, deposit at Base Chest and resume.", Panel.GRAY);
        }
        for (Rule r : list) {
            String id = r.id();
            p.rowLabel(r.describe(), 4, w - 128, r.enabled() ? Panel.WHITE : Panel.GRAY);
            p.toggle("On", r.enabled(), w - 120, 56, v -> {
                rules.setRuleEnabled(id, v);
                p.rebuild();
            });
            p.button("Delete", w - 60, 60, b -> {
                rules.removeRule(id);
                p.rebuild();
            });
            p.nextRow();
        }
        p.dynamic(rules::lastMessage, Panel.GRAY);

        p.text("New rule:", Panel.GRAY);
        Rule.ConditionType ct = enumState(p, K_COND, Rule.ConditionType.INVENTORY_FULL_PCT);
        Rule.Comparison cmp = enumState(p, K_CMP, Rule.Comparison.AT_LEAST);
        Rule.ActionType at = enumState(p, K_ACTION, Rule.ActionType.DEPOSIT_AT);
        int c1 = w * 34 / 100;
        p.place(cycle(Rule.ConditionType.values(), ct, Rule.ConditionType::label, c1, v -> {
            p.setState(K_COND, v.name());
            p.rebuild();
        }), 0);
        boolean jobStatus = ct == Rule.ConditionType.JOB_STATUS;
        Rule.Comparison[] cmps = jobStatus ? new Rule.Comparison[]{Rule.Comparison.EQUALS, Rule.Comparison.NOT_EQUALS}
                : Rule.Comparison.values();
        if (jobStatus && cmp != Rule.Comparison.EQUALS && cmp != Rule.Comparison.NOT_EQUALS) {
            cmp = Rule.Comparison.EQUALS;
        }
        p.place(cycle(cmps, cmp, Rule.Comparison::symbol, 34, v -> p.setState(K_CMP, v.name())), c1 + 4);
        int rest = w - c1 - 42;
        if (ct.usesText() && !jobStatus) {
            int half = (rest - 4) / 2;
            p.textField(K_COND_ARG, "item id", c1 + 42, half);
            p.textField(K_VALUE, "count", c1 + 46 + half, rest - half - 4);
        } else if (jobStatus) {
            p.textField(K_COND_ARG, "RUNNING, PAUSED, NONE...", c1 + 42, rest);
        } else {
            p.textField(K_VALUE, valueHint(ct), c1 + 42, rest);
        }
        p.nextRow();
        p.place(cycle(Rule.ActionType.values(), at, Rule.ActionType::label, c1, v -> {
            p.setState(K_ACTION, v.name());
            p.rebuild();
        }), 0);
        int flagW = at == Rule.ActionType.DEPOSIT_AT || at == Rule.ActionType.TOGGLE_MODULE ? 86 : 0;
        if (at.usesText()) {
            p.textField(K_ACTION_ARG, actionHint(at), c1 + 4, w - c1 - 8 - flagW);
        }
        if (flagW > 0) {
            boolean flag = !"0".equals(p.state(K_FLAG, "1"));
            String label = at == Rule.ActionType.DEPOSIT_AT ? (flag ? "+ resume" : "no resume") : (flag ? "on" : "off");
            p.button(label, w - flagW, flagW, b -> {
                p.setState(K_FLAG, flag ? "0" : "1");
                p.rebuild();
            });
        }
        p.nextRow();
        p.textField(K_COOLDOWN, "cooldown ticks (200)", 0, c1);
        p.button("Add rule", c1 + 4, w - c1 - 4, b -> addRule(p, rules));
        p.nextRow();
    }

    private static void addRule(Panel p, LocalRules rules) {
        Rule.ConditionType ct = enumState(p, K_COND, Rule.ConditionType.INVENTORY_FULL_PCT);
        Rule.Comparison cmp = enumState(p, K_CMP, Rule.Comparison.AT_LEAST);
        Rule.ActionType at = enumState(p, K_ACTION, Rule.ActionType.DEPOSIT_AT);
        double value = 0;
        if (ct != Rule.ConditionType.JOB_STATUS) {
            try {
                value = Double.parseDouble(p.state(K_VALUE, "").trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                p.toast("Enter a number for the condition");
                return;
            }
        } else if (cmp != Rule.Comparison.NOT_EQUALS) {
            cmp = Rule.Comparison.EQUALS;
        }
        String condArg = p.state(K_COND_ARG, "").trim();
        if (ct.usesText() && condArg.isEmpty()) {
            p.toast(ct == Rule.ConditionType.ITEM_COUNT ? "Enter an item id" : "Enter a job status");
            return;
        }
        String actionArg = p.state(K_ACTION_ARG, "").trim();
        if (at.usesText() && actionArg.isEmpty()) {
            p.toast("Enter " + actionHint(at));
            return;
        }
        int cooldown = 200;
        String cd = p.state(K_COOLDOWN, "").trim();
        if (!cd.isEmpty()) {
            try {
                cooldown = Math.max(0, Integer.parseInt(cd));
            } catch (NumberFormatException e) {
                p.toast("Cooldown must be whole ticks");
                return;
            }
        }
        boolean flag = !"0".equals(p.state(K_FLAG, "1"));
        Rule rule = new Rule(rules.newRuleId(), true,
                new Rule.Condition(ct, cmp, value, ct.usesText() ? condArg : ""),
                new Rule.Action(at, at.usesText() ? actionArg : "", flag), cooldown);
        rules.addRule(rule);
        p.setState(K_VALUE, "");
        p.setState(K_COND_ARG, "");
        p.setState(K_ACTION_ARG, "");
        p.toast("Added: " + rule.describe());
        p.rebuild();
    }

    private static String valueHint(Rule.ConditionType ct) {
        return switch (ct) {
            case INVENTORY_FULL_PCT -> "percent, e.g. 90";
            case HEALTH -> "health 0-20";
            case HUNGER -> "food 0-20";
            case TIME_OF_DAY -> "ticks 0-23999 (13000 = night)";
            default -> "value";
        };
    }

    private static String actionHint(Rule.ActionType at) {
        return switch (at) {
            case SUBMIT_GOAL -> "goal text, e.g. gather 32 coal";
            case DEPOSIT_AT -> "waypoint name, e.g. Base Chest";
            case TOGGLE_MODULE -> "module id, e.g. survival_autopilot";
            default -> "";
        };
    }

    private static <E extends Enum<E>> E enumState(Panel p, String key, E def) {
        try {
            return Enum.valueOf(def.getDeclaringClass(), p.state(key, def.name()));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static <E> CycleButton<E> cycle(E[] values, E current, java.util.function.Function<E, String> label, int w,
                                            java.util.function.Consumer<E> set) {
        return CycleButton.<E>builder(v -> Component.literal(label.apply(v)))
                .withValues(values)
                .withInitialValue(current)
                .displayOnlyValue()
                .create(0, 0, w, Panel.WIDGET_H, Component.literal(""), (b, v) -> set.accept(v));
    }

    // ---- profiles ---------------------------------------------------------------------------------------------------

    private static void profiles(Panel p) {
        p.heading("Profiles");
        ProfileConfig active = Services.modState().profile();
        boolean inWorld = !ProfileIds.GLOBAL.equals(active.profileId());
        p.text("Active profile: " + active.profileId() + (inWorld ? "" : " (no world loaded: global defaults)"),
                Panel.WHITE);
        if (!inWorld) {
            return;
        }
        p.button("Copy settings from global", "Replaces this world's module settings with the global profile's", 0,
                200, b -> copyFrom(p, Services.modState().global()));
        p.nextRow();
        List<String> others = savedProfiles(active.profileId());
        if (!others.isEmpty()) {
            p.text("Copy settings from another saved profile:", Panel.GRAY);
        }
        int w = p.width();
        for (String id : others) {
            p.rowLabel(id, 4, w - 96, Panel.WHITE);
            p.button("Copy from", w - 90, 90, b -> {
                ProfileConfig src = CoreBootstrap.configStore().load(id);
                copyFrom(p, src);
            });
            p.nextRow();
        }
    }

    private static void copyFrom(Panel p, ProfileConfig source) {
        ProfileConfig active = Services.modState().profile();
        int n = ProfileCopier.copy(source.root(), active.root());
        CoreBootstrap.modules().applyProfile(active);
        Services.modState().markDirty();
        p.toast("Copied " + n + " module settings from " + source.profileId());
        p.rebuild();
    }

    private static List<String> savedProfiles(String exclude) {
        List<String> out = new ArrayList<>();
        Path dir = CoreBootstrap.configStore().root().resolve("profiles");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && !n.contains(".corrupt-"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .filter(id -> !ProfileIds.fileName(exclude).equals(id))
                    .sorted()
                    .limit(20)
                    .forEach(out::add);
        } catch (IOException e) {
            // listing is best effort
        }
        return out;
    }

    // ---- hotkeys + core ---------------------------------------------------------------------------------------------

    private static void hotkeys(Panel p) {
        p.heading("Hotkeys");
        p.text("Open menu: " + keyName(KeybindManager.mapping(KeybindManager.KeyId.OPEN_MENU)), Panel.WHITE);
        p.text("Stop-All (stops every automation at once): "
                + keyName(KeybindManager.mapping(KeybindManager.KeyId.STOP_ALL)), Panel.WHITE);
        p.text("Clean-Screen (hides every overlay and the HUD line): "
                + keyName(KeybindManager.mapping(KeybindManager.KeyId.CLEAN_SCREEN)), Panel.WHITE);
        if (ToolsBootstrap.containerButtons() != null) {
            p.text("Quick deposit in a chest: " + keyName(ToolsBootstrap.containerButtons().quickDepositKey())
                    + " (Shift = dump all)", Panel.WHITE);
        }
        p.text("Change them in Options > Controls > Key Binds > Utility Suite.", Panel.GRAY);
    }

    private static String keyName(KeyMapping m) {
        return m == null ? "?" : m.getTranslatedKeyMessage().getString();
    }

    private static void coreSettings(Panel p) {
        p.heading("Core settings");
        ProfileConfig global = Services.modState().global();
        SettingsRenderer.settings(p, ModState.CORE, CORE_SPECS, new SettingsAccess(global, ModState.CORE,
                k -> CoreBootstrap.applyCoreSettings(), Services.modState()::markDirty));
        p.text("Clean-Screen is " + (Services.modState().isCleanScreen() ? "ON" : "off") + ".", Panel.GRAY);
    }

    /** Lower-case enum label helper for other tabs. */
    static String pretty(Enum<?> e) {
        return SettingWidgetMapper.prettyEnum(e.name()).toLowerCase(Locale.ROOT);
    }

}
