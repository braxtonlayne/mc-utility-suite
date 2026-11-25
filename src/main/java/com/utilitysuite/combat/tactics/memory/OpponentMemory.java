package com.utilitysuite.combat.tactics.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cross-session memory of opponents: one compact {@link Profile} per (server address, player UUID), blended across
 * fights with an EMA so one odd fight does not overwrite a long history. Engine free; the combat module builds a
 * {@link FightSummary} from {@code OpponentModel} / {@code SkillEstimator} when a fight ends, calls {@link #record},
 * and on engaging a known player reads {@link #lookup} to seed priors and {@link #hint} for the coach line.
 * <p>
 * Memory is capped at {@value #MAX_PROFILES} profiles (least recently seen dropped). Not a tick path: touched once per
 * fight start/end.
 */
public final class OpponentMemory {

    public static final int MAX_PROFILES = 256;
    /** Weight of the newest fight once a profile has history. */
    public static final double ALPHA = 0.3;
    /** Fights needed before {@link #hint} speaks. */
    public static final int MIN_FIGHTS_FOR_HINT = 1;

    /**
     * What one fight showed. NaN = not measured in this fight (kept out of the blend).
     *
     * @param strafeBias     -1 left .. +1 right ({@code OpponentModel.strafeBias})
     * @param shieldUse      share of observed ticks with the shield up, 0..1
     * @param pearlOutHp     the opponent's health when it pearled away, or NaN
     * @param rangedRate     share of its attacks that were ranged (bow/crossbow/trident), 0..1
     * @param maceRate       share of its attacks that were mace dives, 0..1
     * @param reach          learned reach in blocks, or NaN
     * @param cooldownTicks  measured attack cooldown in ticks (CPS proxy), or NaN
     * @param skill          {@code SkillEstimator.skill}, 0..1
     * @param won            true when we won the fight
     */
    public record FightSummary(double strafeBias, double shieldUse, double pearlOutHp, double rangedRate,
                               double maceRate, double reach, double cooldownTicks, double skill, boolean won) {
    }

    /** Blended per-opponent profile. Fields mirror {@link FightSummary}; NaN = never measured. */
    public static final class Profile {
        public String name = "";
        public int fights;
        public int wins;
        public long lastSeen;
        public double strafeBias = Double.NaN;
        public double shieldUse = Double.NaN;
        public double pearlOutHp = Double.NaN;
        public double rangedRate = Double.NaN;
        public double maceRate = Double.NaN;
        public double reach = Double.NaN;
        public double cooldownTicks = Double.NaN;
        public double skill = Double.NaN;

        void blend(FightSummary f) {
            double a = fights == 0 ? 1.0 : ALPHA;
            strafeBias = ema(strafeBias, f.strafeBias(), a);
            shieldUse = ema(shieldUse, f.shieldUse(), a);
            pearlOutHp = ema(pearlOutHp, f.pearlOutHp(), a);
            rangedRate = ema(rangedRate, f.rangedRate(), a);
            maceRate = ema(maceRate, f.maceRate(), a);
            reach = ema(reach, f.reach(), a);
            cooldownTicks = ema(cooldownTicks, f.cooldownTicks(), a);
            skill = ema(skill, f.skill(), a);
            fights++;
            if (f.won()) {
                wins++;
            }
        }
    }

    private final Map<String, Profile> profiles = new HashMap<>();
    private boolean dirty;

    /** Key for a (server, player) pair; server lower-cased, "" for single player. */
    public static String key(String server, String uuid) {
        String s = server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
        return s + "/" + (uuid == null ? "" : uuid.toLowerCase(Locale.ROOT));
    }

    static double ema(double old, double sample, double alpha) {
        if (Double.isNaN(sample) || Double.isInfinite(sample)) {
            return old;
        }
        if (Double.isNaN(old)) {
            return sample;
        }
        return old + alpha * (sample - old);
    }

    /** Blends a finished fight into the profile. */
    public Profile record(String server, String uuid, String name, FightSummary fight, long now) {
        String k = key(server, uuid);
        Profile p = profiles.computeIfAbsent(k, x -> new Profile());
        p.blend(fight);
        if (name != null && !name.isEmpty()) {
            p.name = name;
        }
        p.lastSeen = now;
        trim();
        dirty = true;
        return p;
    }

    /** The profile for this pair, or null when never fought. */
    public Profile lookup(String server, String uuid) {
        return profiles.get(key(server, uuid));
    }

    public int size() {
        return profiles.size();
    }

    /** True once since the last {@link #clearDirty}: the store should schedule a debounced write. */
    public boolean dirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    private void trim() {
        if (profiles.size() <= MAX_PROFILES) {
            return;
        }
        List<Map.Entry<String, Profile>> all = new ArrayList<>(profiles.entrySet());
        all.sort(Comparator.comparingLong(e -> e.getValue().lastSeen));
        for (int i = 0; i < all.size() - MAX_PROFILES; i++) {
            profiles.remove(all.get(i).getKey());
        }
    }

    /**
     * Prior skill for {@code SkillEstimator} on engage: the blended skill pulled toward the neutral 0.5 when only a
     * fight or two is known ({@code w = fights / (fights + 2)}).
     */
    public static double priorSkill(Profile p, double neutral) {
        if (p == null || Double.isNaN(p.skill) || p.fights <= 0) {
            return neutral;
        }
        double w = p.fights / (p.fights + 2.0);
        return neutral + w * (p.skill - neutral);
    }

    /** One coach line for a known opponent, or "" when there is nothing worth saying. */
    public static String hint(Profile p) {
        if (p == null || p.fights < MIN_FIGHTS_FOR_HINT) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(p.name.isEmpty() ? "Known opponent" : p.name).append(": ")
                .append(p.wins).append('-').append(p.fights - p.wins);
        if (!Double.isNaN(p.strafeBias) && Math.abs(p.strafeBias) >= 0.25) {
            sb.append(", strafes ").append(p.strafeBias < 0 ? "left" : "right");
        }
        if (!Double.isNaN(p.shieldUse) && p.shieldUse >= 0.3) {
            sb.append(", shields a lot");
        }
        if (!Double.isNaN(p.pearlOutHp)) {
            sb.append(String.format(Locale.ROOT, ", pearls out near %.0f HP", p.pearlOutHp));
        }
        if (!Double.isNaN(p.maceRate) && p.maceRate >= 0.15) {
            sb.append(", mace diver");
        } else if (!Double.isNaN(p.rangedRate) && p.rangedRate >= 0.25) {
            sb.append(", likes ranged");
        }
        if (!Double.isNaN(p.reach) && p.reach >= 3.2) {
            sb.append(String.format(Locale.ROOT, ", long reach %.1f", p.reach));
        }
        return sb.toString();
    }

    // ---- compact JSON: {"v":1,"p":{"key":[name,fights,wins,lastSeen,s,sh,po,r,m,re,cd,sk]}} -------------------------

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        JsonObject ps = new JsonObject();
        for (Map.Entry<String, Profile> e : profiles.entrySet()) {
            Profile p = e.getValue();
            JsonArray a = new JsonArray();
            a.add(p.name);
            a.add(p.fights);
            a.add(p.wins);
            a.add(p.lastSeen);
            for (double d : new double[]{p.strafeBias, p.shieldUse, p.pearlOutHp, p.rangedRate, p.maceRate, p.reach,
                    p.cooldownTicks, p.skill}) {
                a.add(Double.isNaN(d) ? null : Math.round(d * 1000.0) / 1000.0);
            }
            ps.add(e.getKey(), a);
        }
        root.add("p", ps);
        return root;
    }

    public static OpponentMemory fromJson(JsonObject root) {
        OpponentMemory m = new OpponentMemory();
        if (root == null || !root.has("p") || !root.get("p").isJsonObject()) {
            return m;
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("p").entrySet()) {
            if (!e.getValue().isJsonArray() || e.getValue().getAsJsonArray().size() < 12) {
                continue;
            }
            JsonArray a = e.getValue().getAsJsonArray();
            try {
                Profile p = new Profile();
                p.name = a.get(0).isJsonNull() ? "" : a.get(0).getAsString();
                p.fights = a.get(1).getAsInt();
                p.wins = a.get(2).getAsInt();
                p.lastSeen = a.get(3).getAsLong();
                p.strafeBias = num(a.get(4));
                p.shieldUse = num(a.get(5));
                p.pearlOutHp = num(a.get(6));
                p.rangedRate = num(a.get(7));
                p.maceRate = num(a.get(8));
                p.reach = num(a.get(9));
                p.cooldownTicks = num(a.get(10));
                p.skill = num(a.get(11));
                m.profiles.put(e.getKey(), p);
            } catch (RuntimeException ignored) {
                // skip a garbled entry
            }
        }
        return m;
    }

    private static double num(JsonElement e) {
        return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
    }
}
