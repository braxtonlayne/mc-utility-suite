package com.utilitysuite.core.actions;

import com.utilitysuite.core.api.ISuiteActions;
import com.utilitysuite.core.api.SuiteAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The action registry behind {@link ISuiteActions}. Pure Java. */
public final class SuiteActionRegistry implements ISuiteActions {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/actions");

    private final Map<String, SuiteAction> actions = new LinkedHashMap<>();

    @Override
    public synchronized void register(SuiteAction action) {
        actions.put(action.id(), action);
    }

    @Override
    public synchronized boolean unregister(String id) {
        return id != null && actions.remove(id) != null;
    }

    @Override
    public synchronized List<SuiteAction> all() {
        return new ArrayList<>(actions.values());
    }

    @Override
    public synchronized Optional<SuiteAction> byId(String id) {
        return Optional.ofNullable(id == null ? null : actions.get(id));
    }

    @Override
    public boolean run(String id) {
        SuiteAction a;
        synchronized (this) {
            a = id == null ? null : actions.get(id);
        }
        if (a == null) {
            return false;
        }
        try {
            a.run().run();
            return true;
        } catch (RuntimeException e) {
            LOG.error("Action {} failed", id, e);
            return false;
        }
    }
}
