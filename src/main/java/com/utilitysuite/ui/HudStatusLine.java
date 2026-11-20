package com.utilitysuite.ui;

import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;

import java.util.Locale;
import java.util.Objects;

/**
 * The HUD's one status pill. {@link #text()} is the full line, e.g.
 * {@code [Goal] Gathering 32 Diamonds (18/32) — Mining at Y=-54}; the pill itself draws the parts
 * ({@link #progressText()}, {@link #detailText()}, {@link #itemId()}, {@link #status()}). Everything is rebuilt only
 * when one of its inputs changed; an unchanged update compares fields and allocates nothing. Pure Java.
 */
public final class HudStatusLine {

    private String label;
    private JobStatus status;
    private int current = Integer.MIN_VALUE;
    private int target = Integer.MIN_VALUE;
    private String step;
    private String schematic;
    private String text = "";
    private String progressText = "";
    private String detailText = "";
    private String itemId = "";
    private int rebuilds;

    /** Updates from a job snapshot. Returns true when the text changed. */
    public boolean update(JobSnapshot s) {
        if (s == null) {
            return clear();
        }
        return update(s.goalLabel(), s.status(), s.progressCurrent(), s.progressTarget(), s.currentStep());
    }

    public boolean update(String goalLabel, JobStatus st, int cur, int tgt, String now) {
        if (schematic == null && st == status && cur == current && tgt == target && Objects.equals(goalLabel, label)
                && Objects.equals(now, step)) {
            return false;
        }
        schematic = null;
        label = goalLabel;
        status = st;
        current = cur;
        target = tgt;
        step = now;
        progressText = tgt > 0 ? Math.max(0, cur) + "/" + tgt : "";
        String d = detail(st, now);
        detailText = d.isEmpty() ? prettyLabel(goalLabel == null ? "" : goalLabel) : d;
        itemId = itemIdOf(goalLabel);
        return set(format(goalLabel, st, cur, tgt, now));
    }

    /** Updates from the schematic toolkit's card line (when no goal job runs). */
    public boolean updateSchematic(String statusLine) {
        if (statusLine == null) {
            return clear();
        }
        if (statusLine.equals(schematic)) {
            return false;
        }
        schematic = statusLine;
        label = null;
        status = null;
        progressText = "";
        detailText = statusLine;
        itemId = "";
        return set("[Build] " + statusLine);
    }

    /** No job: nothing renders. Returns true when the text changed. */
    public boolean clear() {
        label = null;
        status = null;
        step = null;
        schematic = null;
        current = Integer.MIN_VALUE;
        target = Integer.MIN_VALUE;
        progressText = "";
        detailText = "";
        itemId = "";
        return set("");
    }

    private boolean set(String t) {
        if (t.equals(text)) {
            return false;
        }
        text = t;
        rebuilds++;
        return true;
    }

    /** Current text; empty means "render nothing". */
    public String text() {
        return text;
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** How often the text was rebuilt (tests: change detection; the pill re-measures when it moves). */
    public int rebuilds() {
        return rebuilds;
    }

    /** Status of the goal job, or null for a schematic line / nothing. */
    public JobStatus status() {
        return status;
    }

    /** True when the line comes from the schematic toolkit rather than a goal job. */
    public boolean isSchematic() {
        return schematic != null;
    }

    /** "18/32", or empty without a count target. */
    public String progressText() {
        return progressText;
    }

    /** The current step (Paused / Planning / Replanning; the readable goal label when the step is empty). */
    public String detailText() {
        return detailText;
    }

    /** Registry id of the goal's target item ("minecraft:diamond"), or empty. */
    public String itemId() {
        return itemId;
    }

    /** The raw goal label of the current job, or null. */
    public String goalLabel() {
        return label;
    }

    public static String format(String goalLabel, JobStatus st, int cur, int tgt, String now) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("[Goal] ").append(prettyLabel(goalLabel == null ? "" : goalLabel));
        if (tgt > 0) {
            sb.append(" (").append(Math.max(0, cur)).append('/').append(tgt).append(')');
        }
        String detail = detail(st, now);
        if (!detail.isEmpty()) {
            sb.append(" — ").append(detail);
        }
        return sb.toString();
    }

    /** What the pill shows as the step: Paused / Planning / Replanning, else the trimmed current step. */
    public static String detail(JobStatus st, String now) {
        if (st == JobStatus.PAUSED) {
            return "Paused";
        } else if (st == JobStatus.QUEUED || st == JobStatus.PLANNING) {
            return "Planning";
        } else if (st == JobStatus.REPLANNING) {
            return "Replanning";
        }
        return now == null ? "" : now.trim();
    }

    /**
     * The first registry-id-looking word of a goal label ("Gather 32 minecraft:diamond" → "minecraft:diamond"), or
     * empty. File paths ("C:\schem\house.litematic") are not item ids.
     */
    public static String itemIdOf(String label) {
        if (label == null) {
            return "";
        }
        for (String w : label.trim().split("\\s+")) {
            int c = w.indexOf(':');
            if (c > 0 && c < w.length() - 1 && w.indexOf('/') < 0 && w.indexOf('\\') < 0 && w.indexOf('.') < 0) {
                return w;
            }
        }
        return "";
    }

    /**
     * "Gather 32 minecraft:diamond" → "Gathering 32 Diamonds": verb in -ing form, registry ids as readable names,
     * plural after a count above one.
     */
    public static String prettyLabel(String label) {
        String[] words = label.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(label.length() + 8);
        int lastNumber = -1;
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) {
                continue;
            }
            String out = w;
            if (i == 0) {
                out = gerund(w);
            } else if (w.indexOf(':') > 0 && w.indexOf('/') < 0 && w.indexOf('\\') < 0) {
                out = itemName(w.substring(w.indexOf(':') + 1), lastNumber > 1);
            }
            try {
                lastNumber = Integer.parseInt(w);
            } catch (NumberFormatException e) {
                lastNumber = -1;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(out);
        }
        return sb.toString();
    }

    private static String gerund(String verb) {
        return switch (verb.toLowerCase(Locale.ROOT)) {
            case "gather" -> "Gathering";
            case "craft" -> "Crafting";
            case "build" -> "Building";
            case "travel" -> "Travelling";
            default -> verb;
        };
    }

    private static String itemName(String path, boolean plural) {
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder(path.length() + 2);
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        if (plural && sb.length() > 0 && sb.charAt(sb.length() - 1) != 's') {
            sb.append('s');
        }
        return sb.toString();
    }
}
