package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;

import java.util.List;

/**
 * One optional layer of the fight loop of the {@link CombatAssistant}. Stages are registered once at start-up
 * ({@link CombatAssistant#addStage}) and run in registration order inside their {@link Slot}; the built-in loop is
 * what runs when no stage takes the tick. A stage keeps its own state, owns its own settings (appended to the
 * module's list) and acts only through the {@link FightContext} it is handed, so a new fight behaviour is one new
 * class and one line in the bootstrap.
 * <p>
 * {@link #step} is a hot path (once per client tick per stage while engaged): no allocation, cheap early-out when the
 * stage's setting is off. Client thread only.
 */
public interface FightStage {

    /** Where in the loop a stage runs. */
    enum Slot {
        /** First of all, ahead of the built-in emergency sustain (healing windows, staging consumables). */
        SUSTAIN,
        /** After sustain, the broad tactic and routes; before the aim (threat answers, combo escape, back-offs). */
        PRE_AIM,
        /** After the aim and the shield break; before the hit planner swings (shield duel, hit vetoes). */
        PRE_SWING,
        /** After the swing; in place of the built-in spacing and strafe when it returns true. */
        MOVEMENT
    }

    /** Constant string, unique among stages (diagnostics, tests). */
    String id();

    Slot slot();

    /**
     * @return true when this stage drove the tick: the rest of the loop (later stages and slots, the built-in swing
     *         and movement) is skipped. A stage that returns true must have written the movement keys it wants
     *         ({@link FightContext#move}); the aim is the stage's call before {@link Slot#PRE_SWING}. Returning
     *         false after setting one-tick requests ({@link FightContext#vetoSwing}, {@link FightContext#forceSide},
     *         {@link FightContext#hitPolicy}) is the usual way to shape the built-in loop without replacing it.
     */
    boolean step(CombatSnapshot s, FightContext ctx);

    /** A new target was engaged (also on a target switch). */
    default void onEngage(FightContext ctx) {
    }

    /**
     * The engaged target popped a totem (entity event 35, from the existing pop detection), on fight-clock tick
     * {@code clock} ({@link FightContext#clock}). Client thread, no allocation.
     */
    default void onTargetPop(long clock) {
    }

    /** The fight ended, the world unloaded or Stop-All ran: drop all state. Keys are released by the assistant. */
    default void reset() {
    }

    /**
     * This stage runs {@code key} for the whole fight (its setting is on), whether or not it holds the key right now.
     * A key has one owner while the assistant is engaged: other sources of the same key stand down
     * ({@link CombatAssistant#stageOwns}). Field reads only, no allocation.
     */
    default boolean ownsKey(SimKey key) {
        return false;
    }

    /** Appends this stage's settings. Keys must be unique in the Combat Assistant module. */
    default void specs(List<SettingSpec> out) {
    }

    /** Reads this stage's settings (profile load and every setting change). */
    default void read(ProfileConfig profile, String moduleId) {
    }
}
