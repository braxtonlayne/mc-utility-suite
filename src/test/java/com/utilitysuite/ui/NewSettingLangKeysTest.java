package com.utilitysuite.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NewSettingLangKeysTest {
    private static final String[] KEYS = {
            "setting.utilitysuite.autopilot.black_box_always",
            "setting.utilitysuite.autopilot.black_box_always.tooltip",
            "setting.utilitysuite.combat_reflexes.escape_during_jobs",
            "setting.utilitysuite.combat_reflexes.escape_during_jobs.tooltip"};

    private static String load() throws Exception {
        try (InputStream in = NewSettingLangKeysTest.class.getResourceAsStream("/assets/utilitysuite/lang/en_us.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void newSettingKeysExistAndAreNonEmpty() throws Exception {
        JsonObject o = JsonParser.parseString(load()).getAsJsonObject();
        for (String k : KEYS) {
            assertFalse(!o.has(k) || o.get(k).getAsString().isBlank(), k);
        }
    }

    @Test
    void noDuplicateKeysAmongNewOnes() throws Exception {
        String text = load();
        for (String k : KEYS) {
            assertEquals(text.indexOf("\"" + k + "\""), text.lastIndexOf("\"" + k + "\""), k);
        }
    }
}
