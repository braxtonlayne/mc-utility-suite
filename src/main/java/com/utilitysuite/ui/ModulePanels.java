package com.utilitysuite.ui;

import com.utilitysuite.core.api.IModule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extension point for custom module panels. The menu renders every module generically as a card (header with the enable
 * switch + settings from {@code IConfigurable.settings()}); a module that needs more (a destination box, a table, a
 * Start button) registers a factory here from its bootstrap:
 * <pre>{@code
 * ModulePanels.register("seed_workbench", (panel, module) -> {
 *     int w = panel.width();                       // inner width of the module's card
 *     panel.textField("seed.value", "World seed", 0, w - 66);
 *     panel.button("Apply", w - 60, 60, b -> ...);  // custom-drawn pill button
 *     panel.nextRow();
 * });
 * }</pre>
 * The factory runs inside the module's card, after its header and before its generic settings, every time the tab is
 * (re)built.
 */
public final class ModulePanels {

    /** Builds custom rows for a module into the menu panel. */
    @FunctionalInterface
    public interface Factory {
        void build(Panel panel, IModule module);
    }

    private static final Map<String, Factory> FACTORIES = new ConcurrentHashMap<>();

    private ModulePanels() {
    }

    /** Registers (or replaces) the custom panel of a module id. Safe to call from any bootstrap. */
    public static void register(String moduleId, Factory factory) {
        FACTORIES.put(moduleId, factory);
    }

    /** The registered factory, or null. */
    public static Factory get(String moduleId) {
        return FACTORIES.get(moduleId);
    }
}
