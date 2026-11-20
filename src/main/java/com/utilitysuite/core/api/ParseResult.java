package com.utilitysuite.core.api;

import java.util.List;

/** Outcome of parsing a goal console command: a request, or an error with suggestions. */
public record ParseResult(GoalRequest request, String error, List<String> suggestions) {

    public ParseResult {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public static ParseResult ok(GoalRequest request) {
        return new ParseResult(request, null, List.of());
    }

    public static ParseResult error(String error, List<String> suggestions) {
        return new ParseResult(null, error, suggestions);
    }

    public boolean isOk() {
        return request != null;
    }
}
