package com.utilitysuite.core;

import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.SuiteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Synchronous event bus. Listeners subscribe to an exact record type, or to {@link SuiteEvent} itself to receive every
 * event. Listeners run in subscription order; one failing listener does not stop the others. Client thread only;
 * posting is rare (state changes), so a small per-type list lookup is fine.
 */
public final class SuiteEventBus implements ISuiteEventBus {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/events");

    private final Map<Class<?>, List<Consumer<? super SuiteEvent>>> listeners = new IdentityHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <E extends SuiteEvent> void subscribe(Class<E> type, Consumer<? super E> listener) {
        listeners.computeIfAbsent(type, k -> new ArrayList<>()).add((Consumer<? super SuiteEvent>) listener);
    }

    @Override
    public void post(SuiteEvent event) {
        dispatch(listeners.get(event.getClass()), event);
        dispatch(listeners.get(SuiteEvent.class), event);
    }

    private static void dispatch(List<Consumer<? super SuiteEvent>> list, SuiteEvent event) {
        if (list == null) {
            return;
        }
        // Index loop: a listener may subscribe more listeners while we dispatch.
        for (int i = 0; i < list.size(); i++) {
            try {
                list.get(i).accept(event);
            } catch (RuntimeException e) {
                LOG.error("Listener failed for {}", event, e);
            }
        }
    }
}
