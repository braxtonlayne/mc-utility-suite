package com.utilitysuite.jobs.storage.hall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plans how to tidy a storage hall: every chest gets a category (manual tag, else inferred from its majority contents),
 * then the planner, on a simulated copy of the contents,
 * <ol>
 *   <li>groups enchanted books: each enchantment gets one home book chest (the one already holding most of it) and
 *       copies elsewhere move there;</li>
 *   <li>moves misplaced items to a chest of their own category (partial stacks first, then empty slots);</li>
 *   <li>consolidates partial stacks of the same item inside each chest;</li>
 *   <li>flags chests whose used-slot share reaches {@code nearlyFull}.</li>
 * </ol>
 * The walking job carries out the moves chest by chest (open source, take, open target, put). Engine free; runs off
 * the client thread on a snapshot of {@code StorageMemory}.
 */
public final class HallSortPlanner {

    /** One chest snapshot. {@code item[i]} null = empty; {@code variant[i]} = enchantment key for books, else "". */
    public static final class Chest {
        public final long pos;
        public final String tag;
        public final String[] item;
        public final String[] variant;
        public final int[] count;
        public final int[] max;

        public Chest(long pos, String tag, int slots) {
            this.pos = pos;
            this.tag = tag;
            this.item = new String[slots];
            this.variant = new String[slots];
            java.util.Arrays.fill(this.variant, "");
            this.count = new int[slots];
            this.max = new int[slots];
        }

        public Chest put(int slot, String itemId, String variant, int count, int max) {
            item[slot] = itemId;
            this.variant[slot] = variant == null ? "" : variant;
            this.count[slot] = count;
            this.max[slot] = max;
            return this;
        }

        Chest copy() {
            Chest c = new Chest(pos, tag, item.length);
            System.arraycopy(item, 0, c.item, 0, item.length);
            System.arraycopy(variant, 0, c.variant, 0, item.length);
            System.arraycopy(count, 0, c.count, 0, item.length);
            System.arraycopy(max, 0, c.max, 0, item.length);
            return c;
        }

        int usedSlots() {
            int n = 0;
            for (int i = 0; i < item.length; i++) {
                if (item[i] != null && count[i] > 0) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Move {@code count} items from chest/slot to chest/slot (indices into the input list). */
    public record Move(int fromChest, int fromSlot, int toChest, int toSlot, int count, String itemId) {
    }

    public record Plan(List<Move> moves, String[] categories, List<Integer> nearlyFull) {
    }

    public Plan plan(List<Chest> input, double nearlyFull) {
        List<Chest> chests = new ArrayList<>(input.size());
        for (Chest c : input) {
            chests.add(c.copy());
        }
        String[] cat = new String[chests.size()];
        for (int i = 0; i < cat.length; i++) {
            Chest c = chests.get(i);
            cat[i] = c.tag != null && !c.tag.isEmpty() ? c.tag : StorageCategories.infer(c.item, c.count);
        }
        List<Move> moves = new ArrayList<>();
        groupBooks(chests, cat, moves);
        moveMisplaced(chests, cat, moves);
        for (int i = 0; i < chests.size(); i++) {
            consolidate(chests.get(i), i, moves);
        }
        List<Integer> full = new ArrayList<>();
        for (int i = 0; i < chests.size(); i++) {
            Chest c = chests.get(i);
            if (c.item.length > 0 && c.usedSlots() >= c.item.length * nearlyFull) {
                full.add(i);
            }
        }
        return new Plan(moves, cat, full);
    }

    private static void groupBooks(List<Chest> chests, String[] cat, List<Move> moves) {
        // variant -> (chest -> books)
        Map<String, int[]> held = new HashMap<>();
        for (int i = 0; i < chests.size(); i++) {
            if (!StorageCategories.BOOKS.equals(cat[i])) {
                continue;
            }
            Chest c = chests.get(i);
            for (int s = 0; s < c.item.length; s++) {
                if (isBook(c, s)) {
                    held.computeIfAbsent(c.variant[s], k -> new int[chests.size()])[i] += c.count[s];
                }
            }
        }
        for (Map.Entry<String, int[]> e : held.entrySet()) {
            int[] n = e.getValue();
            int home = 0;
            for (int i = 1; i < n.length; i++) {
                if (n[i] > n[home]) {
                    home = i;
                }
            }
            for (int i = 0; i < n.length; i++) {
                if (i == home || n[i] == 0) {
                    continue;
                }
                Chest c = chests.get(i);
                for (int s = 0; s < c.item.length; s++) {
                    if (isBook(c, s) && c.variant[s].equals(e.getKey())) {
                        transfer(chests, i, s, home, moves);
                    }
                }
            }
        }
    }

    private static boolean isBook(Chest c, int s) {
        return c.item[s] != null && c.count[s] > 0 && StorageCategories.BOOKS.equals(StorageCategories.of(c.item[s]));
    }

    private static void moveMisplaced(List<Chest> chests, String[] cat, List<Move> moves) {
        for (int i = 0; i < chests.size(); i++) {
            if (cat[i] == null) {
                continue; // mixed / unsorted chest: leave it alone
            }
            Chest c = chests.get(i);
            for (int s = 0; s < c.item.length; s++) {
                if (c.item[s] == null || c.count[s] <= 0) {
                    continue;
                }
                String own = StorageCategories.of(c.item[s]);
                if (own.equals(cat[i])) {
                    continue;
                }
                int target = bestTarget(chests, cat, own, c.item[s], c.variant[s], i);
                if (target >= 0) {
                    transfer(chests, i, s, target, moves);
                }
            }
        }
    }

    /** A chest of category {@code want} with room; prefers one already holding the same item. */
    private static int bestTarget(List<Chest> chests, String[] cat, String want, String item, String variant,
                                  int except) {
        int best = -1;
        boolean bestHas = false;
        for (int j = 0; j < chests.size(); j++) {
            if (j == except || !want.equals(cat[j])) {
                continue;
            }
            Chest t = chests.get(j);
            boolean has = false;
            boolean room = false;
            for (int s = 0; s < t.item.length; s++) {
                if (t.item[s] == null || t.count[s] <= 0) {
                    room = true;
                } else if (t.item[s].equals(item) && t.variant[s].equals(variant)) {
                    has = true;
                    room |= t.count[s] < t.max[s];
                }
            }
            if (room && (best < 0 || (has && !bestHas))) {
                best = j;
                bestHas = has;
            }
        }
        return best;
    }

    /** Moves as much of chest {@code from} slot {@code fs} into chest {@code to} as fits, recording the moves. */
    private static void transfer(List<Chest> chests, int from, int fs, int to, List<Move> moves) {
        Chest a = chests.get(from);
        Chest b = chests.get(to);
        String item = a.item[fs];
        String variant = a.variant[fs];
        int max = Math.max(1, a.max[fs]);
        for (int pass = 0; pass < 2 && a.count[fs] > 0; pass++) {
            for (int s = 0; s < b.item.length && a.count[fs] > 0; s++) {
                boolean empty = b.item[s] == null || b.count[s] <= 0;
                boolean partial = !empty && item.equals(b.item[s]) && variant.equals(b.variant[s])
                        && b.count[s] < b.max[s];
                if ((pass == 0 && !partial) || (pass == 1 && !empty)) {
                    continue;
                }
                int room = empty ? max : b.max[s] - b.count[s];
                int n = Math.min(room, a.count[fs]);
                if (empty) {
                    b.item[s] = item;
                    b.variant[s] = variant;
                    b.max[s] = max;
                    b.count[s] = 0;
                }
                b.count[s] += n;
                a.count[fs] -= n;
                moves.add(new Move(from, fs, to, s, n, item));
            }
        }
        if (a.count[fs] <= 0) {
            a.item[fs] = null;
            a.variant[fs] = "";
            a.count[fs] = 0;
        }
    }

    /** Merges partial stacks of the same item+variant inside one chest (later slots into earlier ones). */
    private static void consolidate(Chest c, int index, List<Move> moves) {
        for (int s = 0; s < c.item.length; s++) {
            if (c.item[s] == null || c.count[s] <= 0 || c.count[s] >= c.max[s]) {
                continue;
            }
            for (int t = s + 1; t < c.item.length && c.count[s] < c.max[s]; t++) {
                if (c.item[t] == null || c.count[t] <= 0 || !Objects.equals(c.item[t], c.item[s])
                        || !c.variant[t].equals(c.variant[s])) {
                    continue;
                }
                int n = Math.min(c.max[s] - c.count[s], c.count[t]);
                c.count[s] += n;
                c.count[t] -= n;
                moves.add(new Move(index, t, index, s, n, c.item[s]));
                if (c.count[t] == 0) {
                    c.item[t] = null;
                    c.variant[t] = "";
                }
            }
        }
    }
}
