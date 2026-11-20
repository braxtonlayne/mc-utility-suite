package com.utilitysuite.ui.tabs;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.SettingsAccess;
import com.utilitysuite.ui.SettingsRenderer;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.UiTheme;

import java.util.function.BooleanSupplier;

/**
 * Shared rows for the "Quick actions" cards: every feature that also has a (possibly unbound) key gets a direct
 * control in its tab. A row is "label · state" on the left and one action pill on the right.
 */
final class QuickActions {

    static final int BTN_W = 96;

    private QuickActions() {
    }

    /** "Label   On/Off" with a Start/Stop style pill; the tab rebuilds after the click to refresh the state. */
    static void stateRow(Panel p, String label, String tooltip, boolean active, String onText, String offText,
                         String startLabel, String stopLabel, Runnable action) {
        int w = p.width();
        int bw = Math.min(BTN_W, Math.max(56, w / 4));
        p.rowLabel(label, 0, w - bw - 90, Panel.WHITE).tooltip(tooltip == null ? "" : tooltip);
        int stateX = Math.max(p.font().width(label) + 10, w - bw - 84);
        p.rowLabel(active ? onText : offText, stateX, 76, active ? UiTheme.RUNNING : Panel.GRAY)
                .bullet(active ? UiTheme.RUNNING : UiTheme.TRACK_OFF);
        PillButton b = p.button(active ? stopLabel : startLabel, tooltip, w - bw, bw,
                active ? PillButton.Style.DANGER_SOFT : PillButton.Style.PRIMARY, btn -> {
                    try {
                        action.run();
                    } catch (RuntimeException e) {
                        p.toast("Not available right now");
                    }
                    p.rebuild();
                });
        if (tooltip != null) {
            b.tooltip(tooltip);
        }
        p.nextRow();
    }

    /** "Label" with a single action pill on the right. */
    static void actionRow(Panel p, String label, String tooltip, String buttonLabel, PillButton.Style style,
                          Runnable action) {
        int w = p.width();
        int bw = Math.min(BTN_W, Math.max(56, w / 4));
        p.rowLabel(label, 0, w - bw - 10, Panel.WHITE).tooltip(tooltip == null ? "" : tooltip);
        p.button(buttonLabel, tooltip, w - bw, bw, style, btn -> action.run());
        p.nextRow();
    }

    /** A labelled switch bound to a module's enabled flag. */
    static void moduleSwitch(Panel p, IModule m, String label, String tooltip) {
        p.toggle(label, m.isEnabled(), 0, p.width(), v -> {
            CoreBootstrap.modules().setModuleEnabled(m.id(), v, Services.modState().profile());
            Services.modState().markDirty();
            p.rebuild();
        }).tooltip(tooltip == null ? "" : tooltip);
        p.nextRow();
    }

    /** One generic setting row of a module (same widget as in its card), or nothing when the key is missing. */
    static void settingRow(Panel p, IModule m, String key) {
        if (!(m instanceof IConfigurable c)) {
            return;
        }
        for (SettingSpec s : c.settings()) {
            if (s.key().equals(key)) {
                SettingsRenderer.row(p, s, new SettingsAccess(Services.modState().profile(), m.id(),
                        c::onSettingChanged, Services.modState()::markDirty));
                return;
            }
        }
    }

    static boolean safe(BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
