package com.utilitysuite.devtest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One playtest scenario: an ordered list of steps run on the client tick, plus the checks it recorded. A step returns
 * {@code true} when it is finished; a {@link #waitFor} step that times out records a failed check and aborts the
 * scenario (its {@link #always} cleanup steps still run). An exception thrown by a step does the same.
 */
public final class Scenario {

    public enum Status { PASS, FAIL, NOT_TESTED }

    public record Check(String name, boolean pass, String evidence, boolean notTested) {
    }

    @FunctionalInterface
    public interface Step {
        /** @return true when the step is done */
        boolean tick(int ticksInStep) throws Exception;
    }

    record StepDef(String name, Step step, boolean always) {
    }

    public final String id;
    public final String title;
    final List<StepDef> steps = new ArrayList<>();
    final List<Check> checks = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    long startNanos;
    long endNanos;
    int ticks;
    boolean aborted;
    String abortReason = "";

    public Scenario(String id, String title) {
        this.id = id;
        this.title = title;
    }

    // ---- builder ------------------------------------------------------------------------------------------------

    public Scenario step(String name, Step step) {
        steps.add(new StepDef(name, step, false));
        return this;
    }

    /** A step that runs once. */
    public Scenario run(String name, ThrowingRunnable r) {
        return step(name, t -> {
            r.run();
            return true;
        });
    }

    /** A cleanup step that runs even after the scenario aborted. */
    public Scenario always(String name, ThrowingRunnable r) {
        steps.add(new StepDef(name, t -> {
            r.run();
            return true;
        }, true));
        return this;
    }

    public Scenario waitTicks(int n) {
        return step("wait " + n + " ticks", t -> t >= n);
    }

    /** Waits until the condition holds; after {@code timeoutTicks} records a failed check and aborts. */
    public Scenario waitFor(String name, int timeoutTicks, BooleanSupplier cond) {
        return step(name, t -> {
            if (cond.getAsBoolean()) {
                return true;
            }
            if (t >= timeoutTicks) {
                throw new StepTimeout(name + " (timed out after " + timeoutTicks + " ticks)");
            }
            return false;
        });
    }

    /** Waits until the condition holds or the timeout passes, without failing. */
    public Scenario waitUpTo(String name, int timeoutTicks, BooleanSupplier cond) {
        return step(name, t -> cond.getAsBoolean() || t >= timeoutTicks);
    }

    public Scenario cmd(String command) {
        return step("/" + command, t -> {
            if (t == 0) {
                Harness.command(command);
            }
            return t >= 3;
        });
    }

    /** A command whose text is computed when the step runs (e.g. arena coordinates). */
    public Scenario cmd(Supplier<String> command) {
        return step("/command", t -> {
            if (t == 0) {
                Harness.command(command.get());
            }
            return t >= 3;
        });
    }

    // ---- recording ----------------------------------------------------------------------------------------------

    public boolean check(String name, boolean pass, String evidence) {
        checks.add(new Check(name, pass, evidence, false));
        PlaytestMod.LOG.info("[playtest] {} :: {} -> {} ({})", id, name, pass ? "PASS" : "FAIL", evidence);
        return pass;
    }

    public boolean check(String name, boolean pass, Supplier<String> evidence) {
        return check(name, pass, safe(evidence));
    }

    public void notTested(String name, String why) {
        checks.add(new Check(name, false, why, true));
        PlaytestMod.LOG.info("[playtest] {} :: {} -> NOT TESTED ({})", id, name, why);
    }

    public void note(String text) {
        notes.add(text);
        PlaytestMod.LOG.info("[playtest] {} :: note: {}", id, text);
    }

    public Status status() {
        boolean anyFail = false;
        boolean anyPass = false;
        for (Check c : checks) {
            if (c.notTested) {
                continue;
            }
            if (c.pass) {
                anyPass = true;
            } else {
                anyFail = true;
            }
        }
        if (aborted || anyFail || !errors.isEmpty()) {
            return Status.FAIL;
        }
        return anyPass ? Status.PASS : Status.NOT_TESTED;
    }

    static String safe(Supplier<String> s) {
        try {
            return String.valueOf(s.get());
        } catch (RuntimeException e) {
            return "<evidence failed: " + e + ">";
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    static final class StepTimeout extends RuntimeException {
        StepTimeout(String message) {
            super(message);
        }
    }
}
