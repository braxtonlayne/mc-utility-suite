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

    /**
     * Pearl, ranged and mace habits one fight showed ({@link HabitRecorder#summary}). NaN = not measured (rates need
     * {@link HabitRecorder#MIN_TICKS_FOR_RATES} fight ticks; shares and means need at least one event).
     *
     * @param pearlsPerMin       ender pearls thrown per minute of fight
     * @param pearlLowHpShare    share of pearls thrown at {@link HabitRecorder#LOW_HP} or less
     * @param pearlEscapeShare   share of pearls thrown away from us
     * @param pearlEngageShare   share of pearls thrown toward us
     * @param pearlDistance      the thrower's distance from us at the throw (blocks)
     * @param rangedPerMin       arrows / tridents released per minute
     * @param bowShare           shares of those shots by weapon
     * @param drawTicks          ticks drawn (or charged) before a shot
     * @param rangedRange        the shooter's distance from us at the release (blocks)
     * @param windLaunchesPerMin wind charges thrown at its own feet per minute
     * @param windShotsPerMin    wind charges thrown otherwise (at us) per minute
     * @param divesPerMin        mace dives per minute
     * @param diveHeight         typical dive fall height (blocks)
     */
    public record HabitSummary(double pearlsPerMin, double pearlLowHpShare, double pearlEscapeShare,
                               double pearlEngageShare, double pearlDistance, double rangedPerMin, double bowShare,
                               double crossbowShare, double tridentShare, double drawTicks, double rangedRange,
                               double windLaunchesPerMin, double windShotsPerMin, double divesPerMin,
                               double diveHeight) {

        /** Nothing measured (fights recorded without the habit recorder). */
        public static final HabitSummary NONE = new HabitSummary(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN);
    }

    /** Number of habit values stored after the 12 base entries of a profile's JSON array (format v2). */
    static final int HABIT_FIELDS = 15;

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
        // habits (format v2; NaN in profiles written before them)
        public double pearlsPerMin = Double.NaN;
        public double pearlLowHpShare = Double.NaN;
        public double pearlEscapeShare = Double.NaN;
        public double pearlEngageShare = Double.NaN;
        public double pearlDistance = Double.NaN;
        public double rangedPerMin = Double.NaN;
        public double bowShare = Double.NaN;
        public double crossbowShare = Double.NaN;
        public double tridentShare = Double.NaN;
        public double drawTicks = Double.NaN;
        public double rangedRange = Double.NaN;
        public double windLaunchesPerMin = Double.NaN;
        public double windShotsPerMin = Double.NaN;
        public double divesPerMin = Double.NaN;
        public double diveHeight = Double.NaN;

        double[] habits() {
            return new double[]{pearlsPerMin, pearlLowHpShare, pearlEscapeShare, pearlEngageShare, pearlDistance,
                    rangedPerMin, bowShare, crossbowShare, tridentShare, drawTicks, rangedRange, windLaunchesPerMin,
                    windShotsPerMin, divesPerMin, diveHeight};
        }

        void setHabits(double[] h) {
            pearlsPerMin = h[0];
            pearlLowHpShare = h[1];
            pearlEscapeShare = h[2];
            pearlEngageShare = h[3];
            pearlDistance = h[4];
            rangedPerMin = h[5];
            bowShare = h[6];
            crossbowShare = h[7];
            tridentShare = h[8];
            drawTicks = h[9];
            rangedRange = h[10];
            windLaunchesPerMin = h[11];
            windShotsPerMin = h[12];
            divesPerMin = h[13];
            diveHeight = h[14];
        }

        /**
         * Habit EMA: the first measured value is taken whole (a profile from before the habits had none), later
         * ones blend at {@link #ALPHA}.
         */
        private void blendHabits(HabitSummary h) {
            pearlsPerMin = ema(pearlsPerMin, h.pearlsPerMin(), ALPHA);
            pearlLowHpShare = ema(pearlLowHpShare, h.pearlLowHpShare(), ALPHA);
            pearlEscapeShare = ema(pearlEscapeShare, h.pearlEscapeShare(), ALPHA);
            pearlEngageShare = ema(pearlEngageShare, h.pearlEngageShare(), ALPHA);
            pearlDistance = ema(pearlDistance, h.pearlDistance(), ALPHA);
            rangedPerMin = ema(rangedPerMin, h.rangedPerMin(), ALPHA);
            bowShare = ema(bowShare, h.bowShare(), ALPHA);
            crossbowShare = ema(crossbowShare, h.crossbowShare(), ALPHA);
            tridentShare = ema(tridentShare, h.tridentShare(), ALPHA);
            drawTicks = ema(drawTicks, h.drawTicks(), ALPHA);
            rangedRange = ema(rangedRange, h.rangedRange(), ALPHA);
            windLaunchesPerMin = ema(windLaunchesPerMin, h.windLaunchesPerMin(), ALPHA);
            windShotsPerMin = ema(windShotsPerMin, h.windShotsPerMin(), ALPHA);
            divesPerMin = ema(divesPerMin, h.divesPerMin(), ALPHA);
            diveHeight = ema(diveHeight, h.diveHeight(), ALPHA);
        }

        void blend(FightSummary f, HabitSummary h) {
            if (h != null) {
                blendHabits(h);
            }
            blend(f);
        }

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
        return record(server, uuid, name, fight, null, now);
    }

    /** Blends a finished fight and the habits it showed ({@code habits} may be null) into the profile. */
    public Profile record(String server, String uuid, String name, FightSummary fight, HabitSummary habits,
                          long now) {
        String k = key(server, uuid);
        Profile p = profiles.computeIfAbsent(k, x -> new Profile());
        p.blend(fight, habits);
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
        if (at(p.pearlEngageShare, 0.5)) {
            sb.append(", pearls in to engage");
        }
        if (at(p.pearlsPerMin, 1.0)) {
            sb.append(String.format(Locale.ROOT, ", %.0f pearls/min", p.pearlsPerMin));
        }
        if (maceDiver(p)) {
            sb.append(", mace diver");
            if (!Double.isNaN(p.diveHeight)) {
                sb.append(String.format(Locale.ROOT, " (~%.0f-block dives)", p.diveHeight));
            }
            if (at(p.windLaunchesPerMin, RecalledHabits.WIND_RATE)) {
                sb.append(", wind-charge launches");
            }
        } else if (rangedUser(p)) {
            sb.append(", likes ").append(rangedWeapon(p));
            if (!Double.isNaN(p.rangedRange)) {
                sb.append(String.format(Locale.ROOT, " at ~%.0f blocks", p.rangedRange));
            }
            if (!Double.isNaN(p.drawTicks)) {
                sb.append(String.format(Locale.ROOT, " (%.0f-tick draws)", p.drawTicks));
            }
        }
        if (at(p.windShotsPerMin, RecalledHabits.WIND_RATE)) {
            sb.append(", throws wind charges");
        }
        if (!Double.isNaN(p.reach) && p.reach >= 3.2) {
            sb.append(String.format(Locale.ROOT, ", long reach %.1f", p.reach));
        }
        return sb.toString();
    }

    static boolean at(double v, double min) {
        return !Double.isNaN(v) && v >= min;
    }

    /** A remembered mace diver: dives often, or a good share of its attacks were dives. */
    public static boolean maceDiver(Profile p) {
        return at(p.maceRate, 0.15) || at(p.divesPerMin, RecalledHabits.DIVE_RATE);
    }

    /** A remembered ranged player: a good share of ranged attacks, or regular shots. */
    public static boolean rangedUser(Profile p) {
        return at(p.rangedRate, 0.25) || at(p.rangedPerMin, RecalledHabits.RANGED_RATE);
    }

    /** "the bow" / "the crossbow" / "the trident" by the largest remembered share, else "ranged". */
    static String rangedWeapon(Profile p) {
        double b = Double.isNaN(p.bowShare) ? -1 : p.bowShare;
        double c = Double.isNaN(p.crossbowShare) ? -1 : p.crossbowShare;
        double t = Double.isNaN(p.tridentShare) ? -1 : p.tridentShare;
        if (b < 0 && c < 0 && t < 0) {
            return "ranged";
        }
        if (c > b && c >= t) {
            return "the crossbow";
        }
        return t > b ? "the trident" : "the bow";
    }

    // ---- compact JSON: {"v":2,"p":{"key":[name,fights,wins,lastSeen,s,sh,po,r,m,re,cd,sk(,15 habits)]}} -------------
    // v1 files (12 entries) load with the habits unmeasured; the habit tail is written only once one is measured, and
    // a v1 reader still reads the first 12 entries of a v2 array.

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("v", 2);
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
            double[] habits = p.habits();
            boolean any = false;
            for (double d : habits) {
                any |= !Double.isNaN(d);
            }
            if (any) {
                for (double d : habits) {
                    a.add(Double.isNaN(d) ? null : Math.round(d * 1000.0) / 1000.0);
                }
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
                if (a.size() >= 12 + HABIT_FIELDS) {
                    double[] h = new double[HABIT_FIELDS];
                    for (int i = 0; i < HABIT_FIELDS; i++) {
                        h[i] = num(a.get(12 + i));
                    }
                    p.setHabits(h);
                }
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
