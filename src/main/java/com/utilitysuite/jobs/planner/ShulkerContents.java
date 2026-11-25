package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.NestedContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Engine-free shulker box bookkeeping: which item ids are shulker boxes, nested counts, and which carried boxes to
 * unpack for a demand (fewest placements first: the boxes holding the most of the item).
 */
public final class ShulkerContents {

    private ShulkerContents() {
    }

    /** One unpack: open the {@code index}-th carried box (an id of that colour) and take {@code count} of the item. */
    public record Take(int index, String containerItemId, int count) {
    }

    public static boolean isShulkerBox(String itemId) {
        return itemId != null && itemId.endsWith("shulker_box");
    }

    /** Count of the item across the nested containers. */
    public static int count(List<NestedContainer> nested, String itemId) {
        int sum = 0;
        for (int i = 0, n = nested.size(); i < n; i++) {
            sum += nested.get(i).count(itemId);
        }
        return sum;
    }

    /**
     * Picks boxes to unpack for {@code need} of the item, largest holders first so as few boxes as possible are placed.
     * {@code alreadyTaken} (box index -> count of this item already promised) is respected and not modified.
     *
     * @return the unpacks; their counts sum to at most {@code need}
     */
    public static List<Take> plan(List<NestedContainer> boxes, String itemId, int need,
                                  Map<Integer, Integer> alreadyTaken) {
        List<Take> out = new ArrayList<>();
        if (need <= 0 || boxes.isEmpty()) {
            return out;
        }
        Integer[] order = new Integer[boxes.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(available(boxes, b, itemId, alreadyTaken),
                available(boxes, a, itemId, alreadyTaken)));
        int left = need;
        for (int idx : order) {
            if (left <= 0) {
                break;
            }
            int avail = available(boxes, idx, itemId, alreadyTaken);
            if (avail <= 0) {
                break;
            }
            int take = Math.min(avail, left);
            out.add(new Take(idx, boxes.get(idx).containerItemId(), take));
            left -= take;
        }
        return out;
    }

    private static int available(List<NestedContainer> boxes, int idx, String itemId, Map<Integer, Integer> taken) {
        return boxes.get(idx).count(itemId) - taken.getOrDefault(idx, 0);
    }
}
