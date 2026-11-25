package com.utilitysuite.tools.inventory.loadout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;

/**
 * A named gear preset: armour (feet, legs, chest, head), offhand and the nine hotbar slots. A null entry means "leave
 * that slot alone". Engine free; stored as compact JSON ({@code {"name":..,"slots":["id|ench=lvl|name", ""]}}).
 */
public final class Loadout {

    /** Targets 0-3 armour (feet..head), 4 offhand, 5-13 hotbar 0-8. */
    public static final int TARGETS = 14;
    public static final int OFFHAND_TARGET = 4;
    public static final int HOTBAR_TARGET = 5;

    /** Suggested preset names shown in the UI. */
    public static final String[] PRESET_NAMES = {"Mining", "Nether", "End", "PvP", "Building", "Travel"};

    private final String name;
    private final GearItem[] slots;

    public Loadout(String name, GearItem[] slots) {
        this.name = name == null || name.isBlank() ? "Custom" : name.trim();
        this.slots = Arrays.copyOf(slots, TARGETS);
    }

    public String name() {
        return name;
    }

    /** The wanted item for a target, or null. */
    public GearItem slot(int target) {
        return slots[target];
    }

    public int filledSlots() {
        int n = 0;
        for (GearItem g : slots) {
            if (g != null) {
                n++;
            }
        }
        return n;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        JsonArray a = new JsonArray();
        for (GearItem g : slots) {
            a.add(g == null ? "" : g.encode());
        }
        o.add("slots", a);
        return o;
    }

    /** Null when the JSON is not a loadout. */
    public static Loadout fromJson(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        if (!o.has("name") || !o.has("slots") || !o.get("slots").isJsonArray()) {
            return null;
        }
        JsonArray a = o.getAsJsonArray("slots");
        GearItem[] s = new GearItem[TARGETS];
        for (int i = 0; i < Math.min(TARGETS, a.size()); i++) {
            s[i] = a.get(i).isJsonPrimitive() ? GearItem.decode(a.get(i).getAsString()) : null;
        }
        return new Loadout(o.get("name").getAsString(), s);
    }
}
