package com.utilitysuite.mixin.input;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the recipe book's search box, so {@code ScreenTextEntry} can tell when the user is typing a recipe
 * search (menu movement must not turn those letters into walking).
 * <p>
 * Why a mixin: the box is {@code private EditBox searchBox} (checked against the 21.1.265 sources), it is not a child
 * of the screen, and {@code RecipeBookComponent.isFocused()} always returns false, so no event or public method exposes
 * its focus. Null until the book has been opened once. Read only: nothing is injected or changed.
 */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {

    @Accessor("searchBox")
    EditBox utilitysuite$searchBox();
}
