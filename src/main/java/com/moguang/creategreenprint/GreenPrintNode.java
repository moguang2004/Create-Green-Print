package com.moguang.creategreenprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/** A recipe stored at one coordinate in a Green Print graph. */
public record GreenPrintNode(ResourceLocation id, int x, int y,
                             List<GreenPrintIngredientGroup> ingredientGroups, ItemStack output) {
    public GreenPrintNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ingredientGroups, "ingredientGroups");
        Objects.requireNonNull(output, "output");
        ingredientGroups = List.copyOf(ingredientGroups);
        output = output.copy();
    }

    /** Expands the compact representation back into the grid expected by Minecraft. */
    public List<Ingredient> ingredientGrid() {
        List<Ingredient> grid = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            grid.add(Ingredient.EMPTY);
        }
        for (GreenPrintIngredientGroup group : ingredientGroups) {
            for (int slot : group.slots()) {
                grid.set(slot, group.ingredient());
            }
        }
        return List.copyOf(grid);
    }

    public boolean produces(ItemStack stack) {
        return ItemStack.isSameItemSameTags(output, stack);
    }

    public boolean touches(GreenPrintNode other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) == 1;
    }
}
