package com.moguang.creategreenprint;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/** A shared ingredient and the crafting-grid slots that use it. */
public record GreenPrintIngredientGroup(Ingredient ingredient, List<Integer> slots) {
    public GreenPrintIngredientGroup {
        if (ingredient == null || ingredient.isEmpty()) {
            throw new IllegalArgumentException("An ingredient group must contain an ingredient");
        }
        if (slots == null || slots.isEmpty() || slots.stream().anyMatch(slot -> slot < 0 || slot >= 9)) {
            throw new IllegalArgumentException("An ingredient group must contain valid crafting slots");
        }
        slots = List.copyOf(slots);
    }

    public int count() {
        return slots.size();
    }

    /** Compacts a nine-slot grid without changing the position of any ingredient. */
    public static List<GreenPrintIngredientGroup> compact(List<Ingredient> grid) {
        List<MutableGroup> groups = new ArrayList<>();
        for (int slot = 0; slot < Math.min(9, grid.size()); slot++) {
            Ingredient ingredient = grid.get(slot);
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }

            MutableGroup existing = groups.stream()
                    .filter(group -> sameIngredient(group.ingredient, ingredient))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                groups.add(new MutableGroup(ingredient, slot));
            } else {
                existing.slots.add(slot);
            }
        }
        return groups.stream()
                .map(group -> new GreenPrintIngredientGroup(group.ingredient, group.slots))
                .toList();
    }

    private static boolean sameIngredient(Ingredient first, Ingredient second) {
        ItemStack[] firstItems = first.getItems();
        ItemStack[] secondItems = second.getItems();
        if (firstItems.length != secondItems.length) {
            return false;
        }
        for (ItemStack firstItem : firstItems) {
            boolean found = false;
            for (ItemStack secondItem : secondItems) {
                if (ItemStack.isSameItemSameTags(firstItem, secondItem)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static final class MutableGroup {
        private final Ingredient ingredient;
        private final List<Integer> slots = new ArrayList<>();

        private MutableGroup(Ingredient ingredient, int slot) {
            this.ingredient = ingredient;
            slots.add(slot);
        }
    }
}
