package com.utilitysuite.tools.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rules as compact JSON (stored per profile under {@code local_rules.rules}). Unknown or broken entries are skipped,
 * never fatal. Pure Java (Gson).
 */
public final class RuleCodec {

    private RuleCodec() {
    }

    public static JsonArray toJson(List<Rule> rules) {
        JsonArray arr = new JsonArray();
        for (Rule r : rules) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("enabled", r.enabled());
            o.addProperty("if", r.condition().type().name());
            o.addProperty("cmp", r.condition().comparison().name());
            o.addProperty("value", r.condition().value());
            if (!r.condition().arg().isEmpty()) {
                o.addProperty("ifArg", r.condition().arg());
            }
            o.addProperty("then", r.action().type().name());
            if (!r.action().arg().isEmpty()) {
                o.addProperty("thenArg", r.action().arg());
            }
            o.addProperty("flag", r.action().flag());
            o.addProperty("cooldown", r.cooldownTicks());
            if (r.whileJob()) {
                o.addProperty("whileJob", true); // absent = false, so older rule lists read unchanged
            }
            arr.add(o);
        }
        return arr;
    }

    public static List<Rule> fromJson(JsonElement el) {
        List<Rule> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) {
            return out;
        }
        for (JsonElement e : el.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            try {
                JsonObject o = e.getAsJsonObject();
                Rule.Condition c = new Rule.Condition(
                        Rule.ConditionType.valueOf(str(o, "if", "").toUpperCase(Locale.ROOT)),
                        Rule.Comparison.valueOf(str(o, "cmp", "AT_LEAST").toUpperCase(Locale.ROOT)),
                        o.has("value") ? o.get("value").getAsDouble() : 0.0,
                        str(o, "ifArg", ""));
                Rule.Action a = new Rule.Action(
                        Rule.ActionType.valueOf(str(o, "then", "").toUpperCase(Locale.ROOT)),
                        str(o, "thenArg", ""),
                        o.has("flag") && o.get("flag").getAsBoolean());
                String id = str(o, "id", "");
                if (id.isEmpty()) {
                    id = "r" + out.size();
                }
                out.add(new Rule(id, !o.has("enabled") || o.get("enabled").getAsBoolean(), c, a,
                        o.has("cooldown") ? o.get("cooldown").getAsInt() : 200,
                        o.has("whileJob") && o.get("whileJob").getAsBoolean()));
            } catch (RuntimeException ignored) {
                // skip a malformed or outdated rule
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }
}
