package com.utilitysuite.core.api;

import java.util.function.Consumer;

/** Minimal synchronous in-mod event bus for {@link SuiteEvent}s. Client thread only. */
public interface ISuiteEventBus {

    <E extends SuiteEvent> void subscribe(Class<E> type, Consumer<? super E> listener);

    void post(SuiteEvent event);
}
