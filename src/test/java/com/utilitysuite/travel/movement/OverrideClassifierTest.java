package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.OverrideClassifier.Kind;
import com.utilitysuite.travel.movement.OverrideClassifier.Sensitivity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One row per rule of design section 4.1. */
class OverrideClassifierTest {

    private static Arguments row(String rule, Kind expected, Function<OverrideClassifier, Kind> input) {
        return Arguments.of(rule, expected, input);
    }

    static Stream<Arguments> rules() {
        return Stream.of(
                // noise
                row("stale key held since before TAKEOFF", Kind.NOISE,
                        c -> c.key(true, true, 30, false, false, true, false)),
                row("mouse under 1.5 deg over 4 ticks", Kind.NOISE, c -> c.yaw(1.2, 1.2, 0.3, 0)),
                row("bounce: release and re-press within 1 tick", Kind.NOISE,
                        c -> c.key(true, false, 1, true, false, true, false)),
                // trim
                row("fresh strafe tap of 1 tick", Kind.TRIM, c -> c.key(true, false, 1, false, true, true, false)),
                row("fresh strafe tap of 2 ticks", Kind.TRIM, c -> c.key(true, false, 2, false, true, true, false)),
                row("forward released in the air", Kind.TRIM, c -> c.key(false, false, 0, false, false, true, true)),
                row("mouse yaw under 25 deg from the commit line", Kind.TRIM, c -> c.yaw(8, 12, 2, 0)),
                row("jump pressed in the air (chain request)", Kind.TRIM, c -> c.jumpInAir(true)),
                // axis override
                row("fresh key held 3 ticks", Kind.AXIS_OVERRIDE,
                        c -> c.key(true, false, 3, false, false, true, false)),
                row("mouse opposing the co-steer by more than 6 deg in 5 ticks", Kind.AXIS_OVERRIDE,
                        c -> c.yaw(7, 10, 2, 7)),
                // hard override
                row("sneak pressed", Kind.HARD_OVERRIDE, c -> c.hard(true, 0, 0, false)),
                row("back against a forward plan held 2 ticks", Kind.HARD_OVERRIDE, c -> c.hard(false, 2, 0, false)),
                row("mouse more than 25 deg off the commit line", Kind.HARD_OVERRIDE, c -> c.yaw(10, 26, 3, 0)),
                row("mouse faster than 20 deg in one tick", Kind.HARD_OVERRIDE, c -> c.yaw(21, 21, 21, 0)),
                row("two axes overridden", Kind.HARD_OVERRIDE, c -> c.hard(false, 0, 2, false)),
                row("Stop-All / screen / lease / teleport / world change", Kind.HARD_OVERRIDE,
                        c -> c.hard(false, 0, 0, true)),
                // the negatives that sit right under a threshold
                row("back held 1 tick", Kind.NOISE, c -> c.hard(false, 1, 0, false)),
                row("one axis overridden", Kind.NOISE, c -> c.hard(false, 0, 1, false)),
                row("forward released on the ground", Kind.NOISE, c -> c.key(false, false, 0, false, false, false,
                        true)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void classifies(String rule, Kind expected, Function<OverrideClassifier, Kind> input) {
        assertEquals(expected, input.apply(new OverrideClassifier()), rule);
    }

    @Test
    void sensitivityScalesThresholds() {
        OverrideClassifier c = new OverrideClassifier();
        c.sensitivity = Sensitivity.LOW;
        assertEquals(Kind.TRIM, c.key(true, false, 3, false, false, true, false), "LOW: 3 ticks is not enough");
        assertEquals(Kind.AXIS_OVERRIDE, c.key(true, false, 5, false, false, true, false), "LOW: 3 x 1.5");
        assertEquals(Kind.TRIM, c.yaw(10, 30, 3, 0), "LOW: 30 deg is under 25 x 1.5");
        c.sensitivity = Sensitivity.HIGH;
        assertEquals(Kind.AXIS_OVERRIDE, c.key(true, false, 2, false, false, true, false), "HIGH: 3 x 0.6");
        assertEquals(Kind.HARD_OVERRIDE, c.yaw(10, 16, 3, 0), "HIGH: 25 x 0.6 = 15");
        c.sensitivity = Sensitivity.NORMAL;
        c.relaxed = true;
        assertEquals(Kind.AXIS_OVERRIDE, c.key(true, false, 2, false, false, true, false), "RELAXED: 2 ticks");
    }
}
