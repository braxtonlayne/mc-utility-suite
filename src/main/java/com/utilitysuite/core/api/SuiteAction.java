package com.utilitysuite.core.api;

import java.util.Objects;

/**
 * One named suite action (radial wheel slice, command palette entry): a stable id, a short label, extra search words
 * and the code that runs it on the client thread.
 *
 * @param id       stable lowercase id, e.g. "stop_all"; used in settings (wheel slices)
 * @param label    display name, e.g. "Stop-All"
 * @param keywords extra words the palette matches on (may be empty)
 * @param run      runs the action; client thread
 */
public record SuiteAction(String id, String label, String keywords, Runnable run) {

    public SuiteAction {
        Objects.requireNonNull(id);
        Objects.requireNonNull(run);
        label = label == null ? id : label;
        keywords = keywords == null ? "" : keywords;
    }
}
