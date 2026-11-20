package com.utilitysuite.jobs.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic fuzzy matcher from user words to registry ids: namespaces optional, spaces/underscores/hyphens
 * interchangeable, plural forms, word prefixes ("iron pick" -> iron_pickaxe) and small typos (edit distance).
 * Immutable after construction; built once per id list.
 */
public final class ItemNameMatcher {

    /** Match outcome: an id, or null with ranked suggestions. */
    public record Match(String id, List<String> suggestions, boolean exact) {
        public Match {
            suggestions = List.copyOf(suggestions);
        }

        public boolean found() {
            return id != null;
        }
    }

    private final String[] ids;
    private final String[] paths;
    private final String[][] words;

    public ItemNameMatcher(List<String> registryIds) {
        Set<String> unique = new LinkedHashSet<>(registryIds);
        ids = unique.toArray(new String[0]);
        paths = new String[ids.length];
        words = new String[ids.length][];
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            int c = id.indexOf(':');
            paths[i] = c >= 0 ? id.substring(c + 1) : id;
            words[i] = paths[i].split("_");
        }
    }

    public int size() {
        return ids.length;
    }

    /** Lowercase, namespace kept if given, separators normalized to '_'. */
    static String normalize(String raw) {
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        while (s.contains("__")) {
            s = s.replace("__", "_");
        }
        return s;
    }

    /** Singular candidates of a (path) word or phrase: diamonds->diamond, torches->torch, berries->berry. */
    static List<String> singularForms(String s) {
        List<String> out = new ArrayList<>(4);
        out.add(s);
        if (s.endsWith("ies") && s.length() > 3) {
            out.add(s.substring(0, s.length() - 3) + "y");
        }
        if (s.endsWith("es") && s.length() > 2) {
            out.add(s.substring(0, s.length() - 2));
        }
        if (s.endsWith("s") && s.length() > 1) {
            out.add(s.substring(0, s.length() - 1));
        }
        return out;
    }

    public Match match(String raw) {
        String norm = normalize(raw);
        if (norm.isEmpty()) {
            return new Match(null, List.of(), false);
        }
        String ns = null;
        String path = norm;
        int c = norm.indexOf(':');
        if (c >= 0) {
            ns = norm.substring(0, c);
            path = norm.substring(c + 1);
        }
        // 1. exact (with plural stripping, per word as well: "iron_ingots", "oak_logs")
        for (String form : pluralVariants(path)) {
            String hit = exact(ns, form);
            if (hit != null) {
                return new Match(hit, List.of(), true);
            }
        }
        // 2. word-prefix containment: every input word is a prefix of a distinct id word
        String[] in = path.split("_");
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            if (ns != null && !ids[i].startsWith(ns + ":")) {
                continue;
            }
            if (prefixesMatch(in, words[i])) {
                hits.add(i);
            }
        }
        if (!hits.isEmpty()) {
            hits.sort(Comparator.<Integer>comparingInt(i -> words[i].length).thenComparingInt(i -> paths[i].length())
                    .thenComparingInt(i -> i));
            int best = hits.get(0);
            boolean unique = hits.size() == 1 || words[hits.get(1)].length > words[best].length;
            if (unique) {
                return new Match(ids[best], List.of(), false);
            }
            List<String> sugg = new ArrayList<>();
            for (int k = 0; k < Math.min(5, hits.size()); k++) {
                sugg.add(ids[hits.get(k)]);
            }
            return new Match(null, sugg, false);
        }
        // 3. edit distance on the path (best plural form)
        int bestD = Integer.MAX_VALUE;
        int bestI = -1;
        boolean tie = false;
        int[] dist = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            int d = Integer.MAX_VALUE;
            for (String form : singularForms(path)) {
                d = Math.min(d, editDistance(form, paths[i]));
            }
            dist[i] = d;
            if (d < bestD) {
                bestD = d;
                bestI = i;
                tie = false;
            } else if (d == bestD) {
                tie = true;
            }
        }
        int allowed = Math.max(1, path.length() / 4);
        if (bestI >= 0 && bestD <= allowed && !tie) {
            return new Match(ids[bestI], List.of(), false);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingInt(i -> dist[i]).thenComparingInt(i -> i));
        List<String> sugg = new ArrayList<>();
        for (int k = 0; k < Math.min(5, order.size()); k++) {
            sugg.add(ids[order.get(k)]);
        }
        return new Match(null, sugg, false);
    }

    private List<String> pluralVariants(String path) {
        Set<String> out = new LinkedHashSet<>(singularForms(path));
        // plural on a middle word: "iron_ingots_block" is rare; handle "oak_logs" via last word only
        int u = path.lastIndexOf('_');
        if (u > 0) {
            String head = path.substring(0, u + 1);
            for (String w : singularForms(path.substring(u + 1))) {
                out.add(head + w);
            }
            // first word plural: "diamonds_pickaxe" is unusual; "ingots of iron" not supported
        }
        return new ArrayList<>(out);
    }

    private String exact(String ns, String path) {
        for (int i = 0; i < ids.length; i++) {
            if (paths[i].equals(path) && (ns == null || ids[i].startsWith(ns + ":"))) {
                return ids[i];
            }
        }
        return null;
    }

    private static boolean prefixesMatch(String[] in, String[] target) {
        if (in.length > target.length) {
            return false;
        }
        boolean[] used = new boolean[target.length];
        outer:
        for (String w : in) {
            if (w.isEmpty()) {
                continue;
            }
            for (int j = 0; j < target.length; j++) {
                if (!used[j] && (target[j].startsWith(w) || singularMatches(w, target[j]))) {
                    used[j] = true;
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean singularMatches(String w, String target) {
        for (String f : singularForms(w)) {
            if (f.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Optimal string alignment distance (Levenshtein plus adjacent transposition). */
    static int editDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return d[n][m];
    }
}
