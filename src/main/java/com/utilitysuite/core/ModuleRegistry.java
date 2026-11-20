package com.utilitysuite.core;

import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IModuleRegistry;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Registry of feature modules. Modules register during mod construction. The core ticks enabled modules once per
 * client tick (post) while a world and player exist, iterating a cached array (no allocation).
 * <p>
 * Enabled states are per profile: {@code profile.module(id).enabled}. The value a module has when it registers is its
 * default, used for profiles that have no saved flag, so a module enabled in one world does not leak into another.
 * A module whose {@code onTick} throws is disabled (and logged) instead of spamming every tick.
 */
public final class ModuleRegistry implements IModuleRegistry {

    public static final String ENABLED_KEY = "enabled";
    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/modules");

    /**
     * Dev-only per-module tick timing ({@code -Dutilitysuite.moduleTiming=true}, set by the playtest run). A static
     * final flag, so the JIT drops the timing branch entirely in normal play.
     */
    private static final boolean TIMING = Boolean.getBoolean("utilitysuite.moduleTiming");

    private IModule[] modules = new IModule[0];
    private boolean[] defaults = new boolean[0];
    private List<IModule> view = List.of();
    /** Cumulative onTick nanoseconds per module (same index as {@link #all()}); only advances when TIMING is on. */
    private long[] tickNanos = new long[0];

    @Override
    public synchronized void register(IModule module) {
        for (IModule m : modules) {
            if (m.id().equals(module.id())) {
                throw new IllegalStateException("Duplicate module id " + module.id());
            }
        }
        IModule[] next = Arrays.copyOf(modules, modules.length + 1);
        next[modules.length] = module;
        boolean[] nextDefaults = Arrays.copyOf(defaults, defaults.length + 1);
        nextDefaults[defaults.length] = module.isEnabled();
        tickNanos = Arrays.copyOf(tickNanos, next.length);
        modules = next;
        defaults = nextDefaults;
        view = List.of(next);
    }

    /** Cumulative per-module onTick nanoseconds (index matches {@link #all()}); all zero unless timing is enabled. */
    public long[] tickNanos() {
        return tickNanos;
    }

    @Override
    public List<IModule> all() {
        return view;
    }

    @Override
    public List<IModule> byCategory(ModuleCategory category) {
        List<IModule> out = new ArrayList<>();
        for (IModule m : modules) {
            if (m.category() == category) {
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public Optional<IModule> byId(String id) {
        for (IModule m : modules) {
            if (m.id().equals(id)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    @Override
    public <T extends IModule> Optional<T> find(Class<T> type) {
        for (IModule m : modules) {
            if (type.isInstance(m)) {
                return Optional.of(type.cast(m));
            }
        }
        return Optional.empty();
    }

    /** Ticks every enabled module. Hot path: array iteration, no allocation. */
    public void tick() {
        IModule[] ms = modules;
        long[] timing = tickNanos;
        for (int i = 0; i < ms.length; i++) {
            IModule m = ms[i];
            if (!m.isEnabled()) {
                continue;
            }
            try {
                if (TIMING && i < timing.length) {
                    long t0 = System.nanoTime();
                    m.onTick();
                    timing[i] += System.nanoTime() - t0;
                } else {
                    m.onTick();
                }
            } catch (RuntimeException e) {
                LOG.error("Module {} threw in onTick; disabling it", m.id(), e);
                try {
                    m.setEnabled(false);
                } catch (RuntimeException ignored) {
                    // already logged above
                }
            }
        }
    }

    /** Restores enabled flags from the profile (or each module's default) and calls {@code onProfileLoaded}. */
    public void applyProfile(ProfileConfig profile) {
        IModule[] ms = modules;
        for (int i = 0; i < ms.length; i++) {
            IModule m = ms[i];
            try {
                boolean enabled = profile.getBool(m.id(), ENABLED_KEY, defaults[i]);
                if (m.isEnabled() != enabled) {
                    m.setEnabled(enabled);
                }
                m.onProfileLoaded(profile);
            } catch (RuntimeException e) {
                LOG.error("Module {} failed to load profile {}", m.id(), profile.profileId(), e);
            }
        }
    }

    /** Writes every module's current enabled flag into the profile (before saving). */
    public void captureEnabledStates(ProfileConfig profile) {
        for (IModule m : modules) {
            profile.set(m.id(), ENABLED_KEY, m.isEnabled());
        }
    }

    /** Enables/disables a module by id and records it in the profile. Returns false for an unknown id. */
    public boolean setModuleEnabled(String id, boolean enabled, ProfileConfig profile) {
        for (IModule m : modules) {
            if (m.id().equals(id)) {
                m.setEnabled(enabled);
                if (profile != null) {
                    profile.set(id, ENABLED_KEY, enabled);
                }
                return true;
            }
        }
        return false;
    }

    /** World/server left: every module drops world-scoped state. */
    public void worldUnloaded() {
        for (IModule m : modules) {
            try {
                m.onWorldUnload();
            } catch (RuntimeException e) {
                LOG.error("Module {} failed in onWorldUnload", m.id(), e);
            }
        }
    }
}
