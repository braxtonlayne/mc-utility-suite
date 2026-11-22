package com.utilitysuite.ui.kit;

import com.utilitysuite.ui.holo.AnimStore;

/**
 * Stable animation keys for widgets, so {@link AnimStore} state (hover glow, toggle slide, segment underline) survives
 * a {@code Panel.rebuild()}: the key comes from what the widget is (module id + setting key), never from the node
 * instance. Pure hashing, no Minecraft imports. Call at build time, not per frame.
 */
public final class WidgetKeys {

    private WidgetKeys() {
    }

    /** Key of the widget of one setting of one module. */
    public static long setting(String moduleId, String settingKey) {
        return AnimStore.key(AnimStore.key(moduleId, 0), AnimStore.key(settingKey, 1));
    }

    /** Key of the enable switch of a module. */
    public static long module(String moduleId) {
        return AnimStore.key(AnimStore.key("module", 0), AnimStore.key(moduleId, 1));
    }

    /** Key from a free-form id such as {@code "ally.nt.<uuid>"} or {@code "chrome.clean"}. */
    public static long named(String id) {
        return AnimStore.key(id, 0);
    }

    /** Fallback key of a node nobody keyed: kind + layout position (stable while the layout is). */
    public static long positional(Class<?> kind, int x, int y) {
        return AnimStore.key(AnimStore.key(kind.getName(), x), y);
    }

    /** Sub-key of one animated channel of a widget (hover, press, slide, ...). */
    public static long channel(long widgetKey, int channel) {
        return AnimStore.key(widgetKey, channel);
    }
}
