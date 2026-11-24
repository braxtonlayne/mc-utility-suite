package com.utilitysuite.core.input;

import com.utilitysuite.mixin.input.RecipeBookComponentAccessor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;

/**
 * "Is the user typing text into this screen?" Anything that reads the physical keyboard while a screen is open (menu
 * movement, in-screen hotkeys) must ask this first, or the letters of a search would also act as keys.
 * <p>
 * Two checks, either one is enough:
 * <ol>
 *   <li>{@link #textFieldFocused}: a text field has keyboard focus. Covers a focused field anywhere down the screen's
 *       focus chain (nested {@link ContainerEventHandler}s), a focused field among the screen's direct children
 *       (creative search, anvil rename) and the recipe book search box. The recipe book is a plain
 *       {@link GuiEventListener}: it reports itself as never focused and keeps its search box in a private field that
 *       is not a child of the screen, so neither {@code screen.getFocused()} nor {@code screen.children()} shows it.</li>
 *   <li>{@link TextEntryGuard}: something consumed a typed character recently. Covers text fields this class cannot
 *       reach, such as another mod's overlay search bar.</li>
 * </ol>
 * Client thread only; allocation free.
 */
public final class ScreenTextEntry {

    private static final int MAX_FOCUS_DEPTH = 8;
    private static final TextEntryGuard GUARD = new TextEntryGuard();

    private ScreenTextEntry() {
    }

    /** True when a text field of {@code screen} has focus or a typed character was consumed within the hold window. */
    public static boolean isActive(Screen screen) {
        return screen != null && (GUARD.isTyping() || textFieldFocused(screen));
    }

    /** True when a text field of {@code screen} has keyboard focus. */
    public static boolean textFieldFocused(Screen screen) {
        if (screen == null) {
            return false;
        }
        GuiEventListener focus = screen.getFocused();
        for (int depth = 0; depth < MAX_FOCUS_DEPTH && focus != null; depth++) {
            if (isTextField(focus)) {
                return true;
            }
            if (!(focus instanceof ContainerEventHandler container)) {
                break;
            }
            focus = container.getFocused();
        }
        List<? extends GuiEventListener> children = screen.children();
        for (int i = 0; i < children.size(); i++) {
            GuiEventListener child = children.get(i);
            if (isTextField(child) && child.isFocused()) {
                return true;
            }
        }
        return screen instanceof RecipeUpdateListener holder && recipeSearchFocused(holder.getRecipeBookComponent());
    }

    private static boolean isTextField(GuiEventListener listener) {
        return listener instanceof EditBox || listener instanceof MultiLineEditBox;
    }

    /** The book only takes keys while visible; its search box can stay "focused" after the book is closed. */
    private static boolean recipeSearchFocused(RecipeBookComponent book) {
        if (book == null || !book.isVisible()) {
            return false;
        }
        EditBox search = ((RecipeBookComponentAccessor) book).utilitysuite$searchBox();
        return search != null && search.isVisible() && search.isFocused();
    }

    // ---- wiring (CoreBootstrap) -------------------------------------------------------------------------------------

    /** Game bus, highest priority, also when cancelled: an overlay that cancels the event took the character. */
    public static void onCharTypedPre(ScreenEvent.CharacterTyped.Pre event) {
        GUARD.onCharOffered();
    }

    /** Game bus: posted only when neither a listener nor the screen handled the character. */
    public static void onCharTypedPost(ScreenEvent.CharacterTyped.Post event) {
        GUARD.onCharUnhandled();
    }

    /** Once per client tick, before the modules run. */
    public static void tick(boolean screenOpen) {
        GUARD.tick(screenOpen);
    }
}
