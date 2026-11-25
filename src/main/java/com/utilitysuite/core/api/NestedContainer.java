package com.utilitysuite.core.api;

import java.util.Map;

/**
 * An item that holds items (a shulker box), seen inside a container or carried by the player. Its contents are not
 * directly usable: a withdrawal has to place and open it first.
 *
 * @param containerItemId item id of the holder ("minecraft:shulker_box", "minecraft:red_shulker_box")
 * @param contents        item id -> count inside it
 */
public record NestedContainer(String containerItemId, Map<String, Integer> contents) {

    public NestedContainer {
        contents = Map.copyOf(contents);
    }

    public int count(String itemId) {
        return contents.getOrDefault(itemId, 0);
    }
}
