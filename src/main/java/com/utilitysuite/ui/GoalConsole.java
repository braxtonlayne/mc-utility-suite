package com.utilitysuite.ui;

import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.PlanPreview;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.kit.UiNode;
import com.utilitysuite.ui.kit.UiRenderUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

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

    // ---- drawing: the parse preview card and the inline error (kit nodes; the Jobs tab places them) -----------------

    /** Greedy word wrap with a width function (pure, for tests); a word wider than the line is cut. */
    static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (width.applyAsInt(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (width.applyAsInt(rest) > maxWidth && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && width.applyAsInt(rest.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** The error of a goal that could not be read: a DANGER hairline on the left and the message beside it. */
    public static UiNode errorLine(Font font, String message, int width) {
        return new ErrorLine(font, message, width);
    }

    /** The dry-run preview as a framed card (target, numbered steps, missing materials, warnings). */
    public static UiNode previewCard(Font font, PlanPreview preview, int width, int maxSteps) {
        return new PreviewCard(font, preview, width, maxSteps);
    }

    /**
     * The duration estimate and required-tools lines of a preview ({@code lang} resolves a key plus format args to
     * text; the game passes {@code I18n::get}). Empty when the preview carries neither.
     */
    static List<String> summaryLines(PlanPreview pv, java.util.function.BiFunction<String, Object[], String> lang) {
        List<String> out = new ArrayList<>(2);
        if (pv.estimatedSeconds() > 0) {
            out.add(lang.apply("ui.utilitysuite.preview.estimate",
                    new Object[]{duration(pv.estimatedSeconds(), lang)}));
        }
        if (!pv.requiredTools().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String t : pv.requiredTools()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(com.utilitysuite.jobs.planner.DisplayNames.of(t));
            }
            out.add(lang.apply("ui.utilitysuite.preview.tools", new Object[]{sb.toString()}));
        }
        return out;
    }

    /** "45 s", "12 min" (rounded up to a whole minute from 60 s), "1 h 5 min". */
    static String duration(int seconds, java.util.function.BiFunction<String, Object[], String> lang) {
        if (seconds < 60) {
            return lang.apply("ui.utilitysuite.preview.duration.seconds", new Object[]{seconds});
        }
        int minutes = (seconds + 30) / 60;
        if (minutes < 60) {
            return lang.apply("ui.utilitysuite.preview.duration.minutes", new Object[]{minutes});
        }
        return lang.apply("ui.utilitysuite.preview.duration.hours", new Object[]{minutes / 60, minutes % 60});
    }

    private static final int LINE_H = 11;
    private static final int PAD = 10;

    private static final class ErrorLine extends UiNode {
        private final List<String> lines;

        ErrorLine(Font font, String message, int width) {
            super(width, 0);
            lines = wrap(message, Math.max(20, width - 12), font::width);
            h = Math.max(1, lines.size()) * LINE_H + 4;
        }

        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            HoloFrames.plate(g, sx, sy, w, h, HoloToken.DANGER, 0.06f);
            HoloFrames.vLine(g, sx, sy, sy + h, HoloTheme.argb(HoloToken.DANGER));
            int argb = HoloTheme.argb(HoloToken.DANGER);
            for (int i = 0; i < lines.size(); i++) {
                UiRenderUtils.text(g, font, lines.get(i), sx + 8, sy + 2 + i * LINE_H, argb);
            }
        }
    }

    private static final class PreviewCard extends UiNode {
        private static final String CAPTION = "DRY-RUN PREVIEW";

        private final String target;
        private final List<String> steps = new ArrayList<>();
        private final List<String> stepNumbers = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final int numberW;

        PreviewCard(Font font, PlanPreview pv, int width, int maxSteps) {
            super(width, 0);
            target = UiRenderUtils.ellipsize(font, pv.interpretedTarget(), width - 2 * PAD);
            int n = Math.min(maxSteps, pv.steps().size());
            numberW = font.width(n + ".") + 6;
            int textW = width - 2 * PAD - numberW;
            for (int i = 0; i < n; i++) {
                List<String> wrapped = wrap(pv.steps().get(i), textW, font::width);
                for (int k = 0; k < wrapped.size(); k++) {
                    stepNumbers.add(k == 0 ? (i + 1) + "." : "");
                    steps.add(wrapped.get(k));
                }
            }
            if (pv.steps().size() > n) {
                stepNumbers.add("");
                steps.add("+ " + (pv.steps().size() - n) + " more steps");
            }
            if (pv.steps().isEmpty()) {
                stepNumbers.add("");
                steps.add("No steps needed.");
            }
            if (!pv.missingMaterials().isEmpty()) {
                notes.addAll(wrap("Missing: " + materials(pv.missingMaterials(), 8), width - 2 * PAD, font::width));
            }
            for (String info : summaryLines(pv, I18n::get)) {
                notes.addAll(wrap(info, width - 2 * PAD, font::width));
            }
            for (String warn : pv.warnings()) {
                notes.addAll(wrap("! " + warn, width - 2 * PAD, font::width));
            }
            h = PAD + LINE_H + 2 + LINE_H + 2 + steps.size() * LINE_H + (notes.isEmpty() ? 0 : 4 + notes.size()
                    * LINE_H) + PAD - 2;
        }

        private static String materials(java.util.Map<String, Integer> items, int max) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (java.util.Map.Entry<String, Integer> e : items.entrySet()) {
                if (i++ >= max) {
                    sb.append(", +").append(items.size() - max).append(" more");
                    break;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                String id = e.getKey();
                sb.append(e.getValue()).append(' ').append(id.startsWith("minecraft:") ? id.substring(10) : id);
            }
            return sb.toString();
        }

        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            HoloFrames.frame(g, sx, sy, w, h, FrameLevel.FRAMED, false, HoloToken.GLASS_RAISED, HoloToken.LINE, 1f);
            int y = sy + PAD;
            UiRenderUtils.text(g, font, CAPTION, sx + PAD, y, HoloTheme.argb(HoloToken.ACCENT, 0.85f));
            HoloFrames.hLine(g, sx + PAD + font.width(CAPTION) + 6, sx + w - PAD, y + 4,
                    HoloTheme.argb(HoloToken.LINE_FAINT));
            y += LINE_H + 2;
            UiRenderUtils.text(g, font, target, sx + PAD, y, HoloTheme.argb(HoloToken.TEXT));
            y += LINE_H + 2;
            int numArgb = HoloTheme.argb(HoloToken.TEXT_MUTED);
            int textArgb = HoloTheme.argb(HoloToken.TEXT_SECONDARY);
            for (int i = 0; i < steps.size(); i++) {
                if (!stepNumbers.get(i).isEmpty()) {
                    UiRenderUtils.text(g, font, stepNumbers.get(i), sx + PAD, y, numArgb);
                }
                UiRenderUtils.text(g, font, steps.get(i), sx + PAD + numberW, y, textArgb);
                y += LINE_H;
            }
            if (!notes.isEmpty()) {
                y += 4;
                int warn = HoloTheme.argb(HoloToken.WARN);
                for (String note : notes) {
                    UiRenderUtils.text(g, font, note, sx + PAD, y, warn);
                    y += LINE_H;
                }
            }
        }
    }
}
