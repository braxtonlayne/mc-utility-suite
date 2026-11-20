package com.utilitysuite.core.api;

import java.util.List;
import java.util.Optional;

/** Registry of all feature modules. Modules register during mod construction. */
public interface IModuleRegistry {

    void register(IModule module);

    List<IModule> all();

    List<IModule> byCategory(ModuleCategory category);

    Optional<IModule> byId(String id);

    /** Typed lookup, e.g. {@code find(CombatAssistant.class)}. */
    <T extends IModule> Optional<T> find(Class<T> type);
}
