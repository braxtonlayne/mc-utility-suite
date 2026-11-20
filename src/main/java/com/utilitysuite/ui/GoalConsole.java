package com.utilitysuite.ui;

import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.Services;

/**
 * The Goal Console behind the top-bar command field: Enter asks the goal engine for a dry-run preview; Enter again on
 * the same text (or the Start pill) submits it. Parse errors (with suggestions) are kept in the menu state for the
 * Jobs tab. All state lives in the menu's session state map, so it survives rebuilds and reopening.
 */
public final class GoalConsole {

    public static final String K_GOAL = "jobs.goal";
    public static final String K_ERROR = "jobs.error";
    /** The text the last preview was requested for (a second Enter on the same text submits). */
    public static final String K_PREVIEWED = "jobs.previewed";

    private GoalConsole() {
    }

    /** Enter in the goal field. Returns false when there is nothing to do (empty text, no engine). */
    static boolean enter(SuiteMenuScreen s) {
        String text = s.state(K_GOAL, "").trim();
        if (text.isEmpty()) {
            return false;
        }
        if (text.equals(s.state(K_PREVIEWED, ""))) {
            return submit(s);
        }
        return preview(s);
    }

    /** True when the current text has been previewed and the Start pill should show. */
    static boolean canStart(SuiteMenuScreen s) {
        String text = s.state(K_GOAL, "").trim();
        return !text.isEmpty() && text.equals(s.state(K_PREVIEWED, "")) && s.state(K_ERROR, "").isEmpty();
    }

    static boolean preview(SuiteMenuScreen s) {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            s.setStatus("The goal engine is not loaded");
            return false;
        }
        String text = s.state(K_GOAL, "").trim();
        ParseResult r = goals.parse(text);
        if (!r.isOk()) {
            s.setState(K_ERROR, r.error() + (r.suggestions().isEmpty() ? "" : "  Try: "
                    + String.join(", ", r.suggestions())));
            s.setState(K_PREVIEWED, "");
        } else {
            s.setState(K_ERROR, "");
            s.setState(K_PREVIEWED, text);
            goals.requestPreview(r.request());
            s.setStatus("Planning: " + r.request().label() + "  (Enter again to start)");
        }
        s.requestRebuild();
        return true;
    }

    static boolean submit(SuiteMenuScreen s) {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            s.setStatus("The goal engine is not loaded");
            return false;
        }
        ParseResult r = goals.parse(s.state(K_GOAL, "").trim());
        if (!r.isOk()) {
            s.setState(K_ERROR, r.error());
        } else {
            s.setState(K_ERROR, "");
            long id = goals.submit(r.request());
            s.setState(K_GOAL, "");
            s.setState(K_PREVIEWED, "");
            s.clearGoalField();
            s.setStatus("Queued job #" + id + ": " + r.request().label());
        }
        s.requestRebuild();
        return true;
    }
}
