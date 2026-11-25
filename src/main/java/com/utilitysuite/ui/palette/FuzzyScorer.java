package com.utilitysuite.ui.palette;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Fuzzy ranking for the command palette, engine free. A candidate matches when every query character appears in it in
 * order (case-insensitive, spaces in the query ignored). The score rewards a match at the very start, matches at word
 * starts and runs of consecutive characters, and penalises gaps and long candidates, so "stop" ranks "Stop-All" above
 * "Storage stop list" and "sa" finds "Stop-All". Typing-time only (allocates the result list).
 */
public final class FuzzyScorer {

    /** Returned by {@link #score} when the query does not match. */
    public static final int NO_MATCH = Integer.MIN_VALUE;

    private FuzzyScorer() {
    }

    public static int score(String query, String candidate) {
        if (candidate == null) {
            return NO_MATCH;
        }
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).replace(" ", "");
        if (q.isEmpty()) {
            return 0;
        }
        String c = candidate.toLowerCase(Locale.ROOT);
        int score = 0;
        int ci = 0;
        int prev = -2;
        int run = 0;
        for (int qi = 0; qi < q.length(); qi++) {
            char ch = q.charAt(qi);
            int found = -1;
            for (int i = ci; i < c.length(); i++) {
                if (c.charAt(i) == ch) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return NO_MATCH;
            }
            score += 10;
            if (found == 0) {
                score += 25;
            } else if (!Character.isLetterOrDigit(c.charAt(found - 1))) {
                score += 15; // word start
            }
            if (found == prev + 1) {
                run++;
                score += 8 * run;
            } else {
                run = 0;
                if (prev >= 0) {
                    score -= Math.min(10, found - prev - 1);
                }
            }
            prev = found;
            ci = found + 1;
        }
        if (c.contains(q)) {
            score += 20; // exact substring
        }
        return score - candidate.length() / 4;
    }

    /** A ranked candidate. */
    public record Ranked<T>(T item, int score) {
    }

    /** Every matching item, best first (ties keep input order), at most {@code limit}. */
    public static <T> List<Ranked<T>> rank(String query, List<T> items, Function<T, String> text, int limit) {
        List<Ranked<T>> out = new ArrayList<>();
        for (T it : items) {
            int s = score(query, text.apply(it));
            if (s != NO_MATCH) {
                out.add(new Ranked<>(it, s));
            }
        }
        out.sort(Comparator.comparingInt((Ranked<T> r) -> r.score).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
