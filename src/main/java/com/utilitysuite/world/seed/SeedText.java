package com.utilitysuite.world.seed;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Seed parsing: {@code /seed} chat output and manual input (vanilla {@code WorldOptions.parseSeed} semantics). */
public final class SeedText {

    /** Vanilla "commands.seed.success" renders as {@code Seed: [<number>]}; the brackets come from the copy button. */
    private static final Pattern SEED_MESSAGE = Pattern.compile("^\\s*Seed:\\s*\\[?(-?\\d{1,20})]?\\s*$");

    private SeedText() {
    }

    /** Parses the plain text of a {@code /seed} response. Empty when the message is something else. */
    public static OptionalLong parseSeedMessage(String message) {
        if (message == null) {
            return OptionalLong.empty();
        }
        Matcher m = SEED_MESSAGE.matcher(message);
        if (!m.matches()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(m.group(1)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Manual input like the world-creation screen: a number is used as-is, other text uses {@code String.hashCode()},
     * blank input is empty.
     */
    public static OptionalLong parseManualSeed(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String s = input.strip();
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return OptionalLong.of(s.hashCode());
        }
    }
}
