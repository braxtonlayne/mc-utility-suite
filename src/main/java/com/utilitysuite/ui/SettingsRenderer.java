package com.utilitysuite.ui;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Generic settings UI: renders a module's enable toggle, its custom {@link ModulePanels} rows and its
 * {@code IConfigurable.settings()} grouped by {@code group}, with advanced settings in a collapsed drawer.
 * Widget choice and value math come from {@link SettingWidgetMapper}; reads/writes go through {@link SettingsAccess}
 * (active profile, {@code onSettingChanged}, {@code markDirty}).
 */
public final class SettingsRenderer {

    private SettingsRenderer() {
    }

    /** Header (name + ON/OFF), custom panel, settings. */
    public static void module(Panel p, IModule m) {
        module(p, m, true);
    }

    public static void module(Panel p, IModule m, boolean withToggle) {
        String name = m instanceof IConfigurable c ? c.displayName() : m.id();
        p.heading(name);
        if (withToggle) {
            moduleToggle(p, m, "Enabled", 0, Math.min(160, p.width()));
            p.nextRow();
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

    /** The module's enable flag as an ON/OFF button (persisted per profile). */
    public static CycleButton<Boolean> moduleToggle(Panel p, IModule m, String label, int dx, int w) {
        return p.toggle(label, m.isEnabled(), dx, w, v -> {
            CoreBootstrap.modules().setModuleEnabled(m.id(), v, Services.modState().profile());
            Services.modState().markDirty();
            p.rebuild();
        });
    }

    /** Settings rows with group headings and the Advanced drawer ({@code drawerKey} = its open/closed state). */
    public static void settings(Panel p, String drawerKey, List<SettingSpec> specs, SettingsAccess access) {
        if (specs.isEmpty()) {
            return;
        }
        boolean drawerShown = false;
        String openKey = "drawer." + drawerKey;
        boolean open = p.flag(openKey);
        for (SettingWidgetMapper.Section s : SettingWidgetMapper.layout(specs)) {
            if (s.advanced() && !drawerShown) {
                drawerShown = true;
                p.button((open ? "▾ " : "▸ ") + "Advanced", 0, 110, b -> {
                    p.setFlag(openKey, !open);
                    p.rebuild();
                });
                p.nextRow();
            }
            if (s.advanced() && !open) {
                continue;
            }
            if (!s.group().isEmpty()) {
                p.text(s.group(), Panel.GRAY);
            }
            for (SettingSpec spec : s.specs()) {
                row(p, spec, access);
            }
        }
    }

    /** One "label ........ [widget]" row. */
    public static void row(Panel p, SettingSpec spec, SettingsAccess access) {
        int labelW = p.width() * 52 / 100;
        int wx = labelW + 4;
        int ww = p.width() - wx;
        p.rowLabel(spec.label(), 4, labelW - 6, Panel.WHITE);
        AbstractWidget w = widget(p, spec, access, ww);
        if (!spec.tooltip().isEmpty()) {
            w.setTooltip(Tooltip.create(Component.literal(spec.tooltip())));
        }
        p.place(w, wx);
        p.nextRow();
    }

    static AbstractWidget widget(Panel p, SettingSpec spec, SettingsAccess access, int w) {
        Component label = Component.literal(spec.label());
        return switch (SettingWidgetMapper.kindOf(spec)) {
            case TOGGLE -> CycleButton.onOffBuilder(access.getBool(spec)).displayOnlyValue()
                    .create(0, 0, w, Panel.WIDGET_H, label, (b, v) -> access.setBool(spec, v));
            case CYCLE -> CycleButton.<String>builder(v -> Component.literal(SettingWidgetMapper.prettyEnum(v)))
                    .withValues(spec.options())
                    .withInitialValue(access.getString(spec))
                    .displayOnlyValue()
                    .create(0, 0, w, Panel.WIDGET_H, label, (b, v) -> access.setString(spec, v));
            case SLIDER -> new SpecSlider(spec, access, w);
            case NUMBER_FIELD -> {
                EditBox box = new EditBox(p.font(), 0, 0, w, Panel.WIDGET_H, label);
                box.setValue(SettingWidgetMapper.format(spec, access.getNumber(spec)));
                box.setFilter(t -> t.isEmpty() || t.matches("-?[0-9]*[.,]?[0-9]*"));
                box.setResponder(t -> {
                    Double v = SettingWidgetMapper.parseNumber(spec, t);
                    if (v != null) {
                        access.setNumber(spec, v);
                    }
                });
                yield box;
            }
            case TEXT_FIELD -> {
                EditBox box = new EditBox(p.font(), 0, 0, w, Panel.WIDGET_H, label);
                box.setMaxLength(512);
                box.setValue(access.getString(spec));
                box.setResponder(t -> access.setString(spec, t));
                yield box;
            }
        };
    }

    /** Slider for INT/DOUBLE specs; writes the snapped value as it moves. */
    static final class SpecSlider extends AbstractSliderButton {
        private final SettingSpec spec;
        private final SettingsAccess access;
        private double current;

        SpecSlider(SettingSpec spec, SettingsAccess access, int width) {
            super(0, 0, width, Panel.WIDGET_H, Component.empty(),
                    SettingWidgetMapper.toSliderPosition(spec, access.getNumber(spec)));
            this.spec = spec;
            this.access = access;
            this.current = SettingWidgetMapper.clamp(spec, access.getNumber(spec));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(SettingWidgetMapper.format(spec, current)));
        }

        @Override
        protected void applyValue() {
            double v = SettingWidgetMapper.fromSliderPosition(spec, value);
            if (v != current) {
                current = v;
                access.setNumber(spec, v);
            }
            updateMessage();
        }
    }
}
