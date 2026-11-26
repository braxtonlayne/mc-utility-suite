package com.utilitysuite.jobs.planner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The recipes the 26.3 client can plan and craft with. Recipes live on the server since 1.21.2, so the catalogue reads,
 * in order:
 * <ol>
 * <li>the integrated server's recipe manager (single player / LAN host): every recipe, exactly as before;</li>
 * <li>recipes a server-side mod synced to the client ({@code RecipesReceivedEvent});</li>
 * <li>otherwise the client recipe book: only the recipes the player has unlocked (ids {@code book:<display id>}).</li>
 * </ol>
 * Recipe-book placement needs a {@code RecipeDisplayId}: {@link #bookIndex} finds it for an entry the player has
 * unlocked. Client thread only; nothing here runs per tick (craft starts, planner rebuilds).
 */
public final class McRecipeCatalog {

    /** Prefix of ids for recipes known only through the client recipe book. */
    public static final String BOOK_PREFIX = "book:";

    /** Where the current recipes come from. */
    public enum Source { NONE, SERVER, SYNCED, BOOK }

    /**
     * One craftable recipe.
     *
     * @param id      planner id (recipe key, or {@code book:<n>})
     * @param kind    planner recipe family
     * @param display the first display of the recipe (shape and ingredients)
     * @param key     recipe key, null for book-only entries
     * @param recipe  the recipe itself when known (server or synced), else null
     * @param bookId  display id for a book-only entry, else -1
     */
    public record Entry(String id, RecipeKind kind, RecipeDisplay display, ResourceKey<Recipe<?>> key,
                        Recipe<?> recipe, int bookId) {

        /** Crafting recipe that does not fit the 2x2 inventory grid (the 1.21.1 {@code !canCraftInDimensions(2, 2)}). */
        public boolean needsTable() {
            if (display instanceof ShapedCraftingRecipeDisplay s) {
                return s.width() > 2 || s.height() > 2;
            }
            if (display instanceof ShapelessCraftingRecipeDisplay s) {
                return s.ingredients().size() > 4;
            }
            return true;
        }

        /** Items one craft makes (the display's result count; 1 when it cannot be resolved). */
        public int resultCount(ClientLevel level) {
            if (level == null) {
                return 1;
            }
            ItemStack st = display.result().resolveForFirstStack(SlotDisplayContext.fromLevel(level));
            return st.isEmpty() ? 1 : st.getCount();
        }

        /** True when the stack is a valid input of this (cooking) recipe. */
        public boolean acceptsInput(ItemStack stack, ClientLevel level) {
            if (recipe instanceof SingleItemRecipe single) {
                return single.input().test(stack);
            }
            if (display instanceof FurnaceRecipeDisplay f && level != null) {
                for (ItemStack s : f.ingredient().resolveForStacks(SlotDisplayContext.fromLevel(level))) {
                    if (s.getItem() == stack.getItem()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static volatile RecipeMap synced = RecipeMap.EMPTY;

    /** Known display ids of the client recipe book (rebuilt when the book's collections change). */
    private static List<RecipeCollection> knownFrom;
    private static final Set<Integer> knownIds = new HashSet<>();
    private static final Map<RecipeDisplay, Integer> knownByDisplay = new HashMap<>();
    private static final List<RecipeDisplayEntry> knownEntries = new ArrayList<>();

    private McRecipeCatalog() {
    }

    /** Recipes a server-side mod synced (NeoForge {@code RecipesReceivedEvent}). */
    public static void onRecipesReceived(RecipeMap map) {
        synced = map == null ? RecipeMap.EMPTY : map;
    }

    /** Logout: forget synced recipes and the recipe-book cache. */
    public static void clear() {
        synced = RecipeMap.EMPTY;
        knownFrom = null;
        knownIds.clear();
        knownByDisplay.clear();
        knownEntries.clear();
    }

    public static Source source(Minecraft mc) {
        if (mc.level == null) {
            return Source.NONE;
        }
        if (serverRecipes(mc) != null) {
            return Source.SERVER;
        }
        if (!synced.values().isEmpty()) {
            return Source.SYNCED;
        }
        return mc.player != null ? Source.BOOK : Source.NONE;
    }

    /**
     * Identity of the current recipe set: the integrated server's recipe manager (replaced on {@code /reload}), the
     * synced map, or the recipe book's collection list (rebuilt on unlock). A change means the planner is stale.
     */
    public static Object origin(Minecraft mc) {
        return switch (source(mc)) {
            case SERVER -> mc.getSingleplayerServer().getRecipeManager();
            case SYNCED -> synced;
            case BOOK -> mc.player.getRecipeBook().getCollections();
            case NONE -> null;
        };
    }

    /** Number of recipe-book entries the player knows (changes on unlock; used to refresh a BOOK catalogue). */
    public static int knownCount(Minecraft mc) {
        refreshKnown(mc.player);
        return knownIds.size();
    }

    private static Collection<RecipeHolder<?>> serverRecipes(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        return server == null ? null : server.getRecipeManager().getRecipes();
    }

    /** Every recipe of a kind the planner understands. */
    public static List<Entry> all(Minecraft mc) {
        List<Entry> out = new ArrayList<>();
        switch (source(mc)) {
            case SERVER -> {
                Collection<RecipeHolder<?>> rs = serverRecipes(mc);
                if (rs != null) {
                    for (RecipeHolder<?> h : rs) {
                        addHolder(h, out);
                    }
                }
            }
            case SYNCED -> {
                for (RecipeHolder<?> h : synced.values()) {
                    addHolder(h, out);
                }
            }
            case BOOK -> {
                refreshKnown(mc.player);
                for (RecipeDisplayEntry e : knownEntries) {
                    Entry entry = fromBook(e, mc.level);
                    if (entry != null) {
                        out.add(entry);
                    }
                }
            }
            case NONE -> {
            }
        }
        return out;
    }

    /** The entry for a planner id, or null when it is unknown in the current session. */
    public static Entry byId(Minecraft mc, String id) {
        if (id == null || mc.level == null) {
            return null;
        }
        if (id.startsWith(BOOK_PREFIX)) {
            int n;
            try {
                n = Integer.parseInt(id.substring(BOOK_PREFIX.length()));
            } catch (NumberFormatException e) {
                return null;
            }
            refreshKnown(mc.player);
            for (RecipeDisplayEntry e : knownEntries) {
                if (e.id().index() == n) {
                    return fromBook(e, mc.level);
                }
            }
            return null;
        }
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) {
            return null;
        }
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, rl);
        IntegratedServer server = mc.getSingleplayerServer();
        RecipeHolder<?> h = server != null ? server.getRecipeManager().byKey(key).orElse(null) : synced.byKey(key);
        return h == null ? null : toEntry(h);
    }

    /**
     * Display id to request recipe-book placement with, or -1 when the player has not unlocked the recipe (or the
     * client cannot tell which book entry it is).
     */
    public static int bookIndex(Minecraft mc, Entry e) {
        LocalPlayer player = mc.player;
        if (player == null || e == null) {
            return -1;
        }
        refreshKnown(player);
        if (e.bookId() >= 0) {
            return knownIds.contains(e.bookId()) ? e.bookId() : -1;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && e.key() != null) {
            int[] found = {-1};
            server.getRecipeManager().listDisplaysForRecipe(e.key(), d -> {
                if (found[0] < 0 && knownIds.contains(d.id().index())) {
                    found[0] = d.id().index();
                }
            });
            if (found[0] >= 0) {
                return found[0];
            }
        }
        // Remote server with synced recipes: the book entry carries the same display the recipe builds.
        Integer byDisplay = knownByDisplay.get(e.display());
        return byDisplay == null ? -1 : byDisplay;
    }

    private static void refreshKnown(LocalPlayer player) {
        if (player == null) {
            return;
        }
        List<RecipeCollection> cols = player.getRecipeBook().getCollections();
        if (cols == knownFrom) {
            return;
        }
        knownFrom = cols;
        knownIds.clear();
        knownByDisplay.clear();
        knownEntries.clear();
        for (RecipeCollection c : cols) {
            for (RecipeDisplayEntry e : c.getRecipes()) {
                if (knownIds.add(e.id().index())) {
                    knownEntries.add(e);
                    knownByDisplay.putIfAbsent(e.display(), e.id().index());
                }
            }
        }
    }

    private static void addHolder(RecipeHolder<?> h, List<Entry> out) {
        Entry e = toEntry(h);
        if (e != null) {
            out.add(e);
        }
    }

    private static Entry toEntry(RecipeHolder<?> h) {
        Recipe<?> r = h.value();
        RecipeKind kind = kindOf(r.getType());
        if (kind == null || r.isSpecial()) {
            return null;
        }
        List<RecipeDisplay> displays = r.display();
        if (displays.isEmpty()) {
            return null;
        }
        return new Entry(h.id().identifier().toString(), kind, displays.get(0), h.id(), r, -1);
    }

    private static Entry fromBook(RecipeDisplayEntry e, ClientLevel level) {
        RecipeDisplay d = e.display();
        RecipeKind kind;
        if (d instanceof ShapedCraftingRecipeDisplay || d instanceof ShapelessCraftingRecipeDisplay) {
            kind = RecipeKind.CRAFTING;
        } else if (d instanceof FurnaceRecipeDisplay f && level != null) {
            ItemStack st = f.craftingStation().resolveForFirstStack(SlotDisplayContext.fromLevel(level));
            String station = st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            kind = switch (station) {
                case Items.FURNACE -> RecipeKind.SMELTING;
                case Items.BLAST_FURNACE -> RecipeKind.BLASTING;
                case Items.SMOKER -> RecipeKind.SMOKING;
                default -> null; // campfire cooking: not planned (as before)
            };
        } else {
            kind = null;
        }
        if (kind == null) {
            return null;
        }
        int n = e.id().index();
        return new Entry(BOOK_PREFIX + n, kind, d, null, null, n);
    }

    private static RecipeKind kindOf(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) {
            return RecipeKind.CRAFTING;
        }
        if (type == RecipeType.SMELTING) {
            return RecipeKind.SMELTING;
        }
        if (type == RecipeType.BLASTING) {
            return RecipeKind.BLASTING;
        }
        if (type == RecipeType.SMOKING) {
            return RecipeKind.SMOKING;
        }
        // Stonecutting is understood by the planner but left out of the in-game snapshot: the stonecutter has no
        // recipe-book placement, so the executor cannot run it yet.
        return null;
    }

    // ---- display helpers --------------------------------------------------------------------------------------------

    /** Ingredient slots of the display in grid order (shaped: width x height, blanks included); null when none. */
    public static List<SlotDisplay> ingredientSlots(RecipeDisplay d) {
        if (d instanceof ShapedCraftingRecipeDisplay s) {
            return s.ingredients();
        }
        if (d instanceof ShapelessCraftingRecipeDisplay s) {
            return s.ingredients();
        }
        if (d instanceof FurnaceRecipeDisplay f) {
            return List.of(f.ingredient());
        }
        return null;
    }

    /** Item ids a slot accepts (empty for a blank slot). */
    public static List<String> optionIds(SlotDisplay slot, ContextMap ctx) {
        List<String> out = new ArrayList<>();
        if (slot instanceof SlotDisplay.Empty) {
            return out;
        }
        for (ItemStack s : slot.resolveForStacks(ctx)) {
            Item item = s.getItem();
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (!s.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
