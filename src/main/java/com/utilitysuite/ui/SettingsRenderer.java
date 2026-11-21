package com.utilitysuite.ui;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.ui.kit.LabelNode;
import com.utilitysuite.ui.kit.ModernSlider;
import com.utilitysuite.ui.kit.StyledTextField;
import com.utilitysuite.ui.kit.ToggleSwitch;
import com.utilitysuite.ui.kit.UiNode;

import java.util.List;
import java.util.Map;

/**
 * Generic settings UI: each module is a card whose header holds its name, a short muted description and the enable
 * switch; the body holds its custom {@link ModulePanels} rows and its basic {@code IConfigurable.settings()} grouped
 * by {@code group}; advanced settings sit in a collapsed {@code CollapsibleSection}. Widget choice and value math come
 * from {@link SettingWidgetMapper}; reads/writes go through {@link SettingsAccess} (active profile,
 * {@code onSettingChanged}, {@code markDirty}).
 */
public final class SettingsRenderer {

    /** Short descriptions for the card headers (the module API has no description field). */
    private static final Map<String, String> BLURBS = Map.ofEntries(
            Map.entry("ally_roster", "Allies and the middle-click shortcut"),
            Map.entry("combat_assistant", "Fights back, or hunts on its own (Proactive)"),
            Map.entry("movement_assist", "Edge jumps, air steering, landing overlay"),
            Map.entry("fight_coach", "Spacing, cooldown and retreat hints"),
            Map.entry("schematic_toolkit", "Import, bill of materials, build assist"),
            Map.entry("storage_memory", "Remembers what your containers hold"),
            Map.entry("survival_autopilot", "Auto-tool and survival policies during jobs"),
            Map.entry("goal_engine", "Goal console and job queue"),
            Map.entry("smart_inventory", "Refill, deposit, shulker previews, auto-armor"),
            Map.entry("local_rules", "Condition → action rules for this profile"),
            Map.entry("travel_autopilot", "Overland, boat, elytra or Nether highway"),
            Map.entry("bridge_assist", "Places blocks along a line while sneaking"),
            Map.entry("terrain_assist", "SafeWalk, Edge Jump, Step, Water Walk, hazards"),
            Map.entry("scout_camera", "Free camera, zoom, fullbright"),
            Map.entry("world_knowledge", "Data sources and prediction ledger"),
            Map.entry("seed_oresim", "World seed and ore predictions"),
            Map.entry("world_overlays", "Outlines of observed blocks by preset"),
            Map.entry("world_radar", "Players, mobs and items around you"),
            Map.entry("waypoints", "Groups, beacons and the breadcrumb trail"),
            Map.entry(SuiteHud.ID, "Job pill and status badges"));

    private SettingsRenderer() {
    }

    /** {name, description}: "Packet Lab (packet inspector + timeline)" splits into both parts. */
    public static String[] describe(IModule m) {
        String name = m instanceof IConfigurable c ? c.displayName() : m.id();
        String desc = BLURBS.getOrDefault(m.id(), "");
        int p = name.indexOf(" (");
        if (p > 0 && name.endsWith(")")) {
            String inner = name.substring(p + 2, name.length() - 1);
            name = name.substring(0, p);
            if (desc.isEmpty()) {
                desc = Character.toUpperCase(inner.charAt(0)) + inner.substring(1);
            } else {
                desc = Character.toUpperCase(inner.charAt(0)) + inner.substring(1) + " · " + desc;
            }
        }
        return new String[]{name, desc};
    }

    /** Card with header (name, description, enable switch), custom panel, settings. */
    public static void module(Panel p, IModule m) {
        module(p, m, true);
    }

    public static void module(Panel p, IModule m, boolean withToggle) {
        String[] d = describe(m);
        p.card(d[0], d[1], withToggle ? ToggleSwitch.W + 8 : 0, com.utilitysuite.ui.kit.CardNode.Tone.NORMAL);
        if (withToggle) {
            moduleToggle(p, m);
        }
        ModulePanels.Factory f = ModulePanels.get(m.id());
        if (f != null) {
            try {
                f.build(p, m);
            } catch (RuntimeException e) {
                p.text("Custom panel failed: " + e.getMessage(), Panel.RED);
            }
        }
        if (m instanceof IConfigurable c) {
            settings(p, m.id(), c.settings(), new SettingsAccess(Services.modState().profile(), m.id(),
                    c::onSettingChanged, Services.modState()::markDirty));
        }
    }

    /** The module's enable flag as the card header switch (persisted per profile). */
    public static ToggleSwitch moduleToggle(Panel p, IModule m) {
        return p.cardToggle(m.isEnabled(), "module." + m.id(), v -> setEnabled(p, m, v));
    }

    static void setEnabled(Panel p, IModule m, boolean v) {
        CoreBootstrap.modules().setModuleEnabled(m.id(), v, Services.modState().profile());
        Services.modState().markDirty();
        p.rebuild();
    }

    /** Settings rows with group headings and the collapsible Advanced block ({@code drawerKey} = its state). */
    public static void settings(Panel p, String drawerKey, List<SettingSpec> specs, SettingsAccess access) {
        if (specs.isEmpty()) {
            return;
        }
        boolean drawerShown = false;
        boolean open = false;
        int advancedCount = 0;
        for (SettingSpec s : specs) {
            if (s.advanced()) {
                advancedCount++;
            }
        }
        for (SettingWidgetMapper.Section s : SettingWidgetMapper.layout(specs)) {
            if (s.advanced() && !drawerShown) {
                drawerShown = true;
                open = p.collapsible("drawer." + drawerKey, "Advanced settings", advancedCount);
            }
            if (s.advanced() && !open) {
                continue;
            }
            if (!s.group().isEmpty()) {
                p.subheading(s.group());
            }
            for (SettingSpec spec : s.specs()) {
                row(p, spec, access);
            }
        }
        if (drawerShown) {
            p.endCollapsible();
        }
    }

    /** One "label ........ [widget]" row; the widget is right-aligned. */
    public static void row(Panel p, SettingSpec spec, SettingsAccess access) {
        int w = p.width();
        SettingWidgetMapper.Kind kind = SettingWidgetMapper.kindOf(spec);
        int ww = kind == SettingWidgetMapper.Kind.TOGGLE ? ToggleSwitch.W : Math.min(200, w * 46 / 100);
        LabelNode label = p.rowLabel(spec.label(), 0, w - ww - 10, Panel.WHITE);
        UiNode node = widget(p, spec, access, ww, w - ww);
        if (!spec.tooltip().isEmpty()) {
            label.tooltip(spec.tooltip());
            node.tooltip(spec.tooltip());
        }
        p.nextRow();
    }

    static UiNode widget(Panel p, SettingSpec spec, SettingsAccess access, int w, int dx) {
        String focusKey = "setting." + access.moduleId() + "." + spec.key();
        return switch (SettingWidgetMapper.kindOf(spec)) {
            case TOGGLE -> p.place(new ToggleSwitch(access.getBool(spec), v -> access.setBool(spec, v)), dx);
            case CYCLE -> p.cycle(spec.options(), access.getString(spec), SettingWidgetMapper::prettyEnum, dx, w,
                    v -> access.setString(spec, v));
            case SLIDER -> p.slider(new SpecModel(spec, access), dx, w);
            case NUMBER_FIELD -> {
                StyledTextField f = p.field(focusKey, "", dx, w);
                f.box().setValue(SettingWidgetMapper.format(spec, access.getNumber(spec)));
                f.box().setFilter(t -> t.isEmpty() || t.matches("-?[0-9]*[.,]?[0-9]*"));
                f.box().setResponder(t -> {
                    Double v = SettingWidgetMapper.parseNumber(spec, t);
                    if (v != null) {
                        access.setNumber(spec, v);
                    }
                });
                yield f;
            }
            case TEXT_FIELD -> {
                StyledTextField f = p.field(focusKey, "", dx, w);
                f.box().setMaxLength(512);
                f.box().setValue(access.getString(spec));
                f.box().setResponder(t -> access.setString(spec, t));
                yield f;
            }
        };
    }

    /** Slider model for INT/DOUBLE specs; writes the snapped value as it moves. */
    static final class SpecModel implements ModernSlider.Model {
        private final SettingSpec spec;
        private final SettingsAccess access;
        private double current;

        SpecModel(SettingSpec spec, SettingsAccess access) {
            this.spec = spec;
            this.access = access;
            this.current = SettingWidgetMapper.clamp(spec, access.getNumber(spec));
        }

        @Override
        public double position() {
            return SettingWidgetMapper.toSliderPosition(spec, current);
        }

        @Override
        public boolean setPosition(double position) {
            return apply(SettingWidgetMapper.fromSliderPosition(spec, position));
        }

        @Override
        public boolean step(int direction) {
            // next grid value above/below, also when the stored value sits between grid points
            double st = SettingWidgetMapper.step(spec);
            double base = (current - spec.min()) / st;
            double k = direction > 0 ? Math.floor(base + 1e-9) + 1 : Math.ceil(base - 1e-9) - 1;
            double raw = spec.min() + k * st;
            return apply(SettingWidgetMapper.fromSliderPosition(spec, SettingWidgetMapper.toSliderPosition(spec, raw)));
        }

        private boolean apply(double v) {
            if (v == current) {
                return false;
            }
            current = v;
            access.setNumber(spec, v);
            return true;
        }

        @Override
        public String text() {
            return SettingWidgetMapper.format(spec, current);
        }

        @Override
        public String widestText() {
            String a = SettingWidgetMapper.format(spec, spec.min());
            String b = SettingWidgetMapper.format(spec, spec.max());
            return a.length() > b.length() ? a : b;
        }
    }
}
