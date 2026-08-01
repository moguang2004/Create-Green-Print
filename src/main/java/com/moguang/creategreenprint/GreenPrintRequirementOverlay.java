package com.moguang.creategreenprint;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.simibubi.create.content.equipment.blueprint.BlueprintOverlayRenderer;

import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.EntityHitResult;

/** Supplies Green Print data to Create's own blueprint overlay renderer. */
public final class GreenPrintRequirementOverlay {
    private static final Field INGREDIENTS_FIELD = overlayField("ingredients");
    private static final Map<Ingredient, Map<Integer, Boolean>> RECURSIVE_REQUIREMENTS = new IdentityHashMap<>();
    private static final Map<Ingredient, Boolean> PRODUCER_RECIPES = new IdentityHashMap<>();
    private static final Map<ItemStack, ChatFormatting> MISSING_COUNT_COLORS = new IdentityHashMap<>();

    private static UUID cachedEntity;
    private static long cachedAt = Long.MIN_VALUE;

    private GreenPrintRequirementOverlay() {
    }

    /** Invoked at the head of Create's BlueprintOverlayRenderer.renderOverlay. */
    public static void prepareForCreateOverlay() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null || minecraft.player == null
                || !(minecraft.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof GreenPrintEntity greenPrint)) {
            clearRenderState();
            return;
        }

        if (!greenPrint.hasRecipeAt(hit.getLocation().subtract(greenPrint.position()))) {
            clearRenderState();
            return;
        }

        List<Pair<ItemStack, Boolean>> ingredients = overlayIngredients();
        GreenPrintNode node = greenPrint.recipeNodeAt(hit.getLocation().subtract(greenPrint.position()));
        if (ingredients == null || ingredients.isEmpty() || node == null) {
            clearRenderState();
            return;
        }

        List<Requirement> requirements = requirementsFor(node, minecraft.player);
        if (requirements.isEmpty()) {
            clearRenderState();
            return;
        }

        long gameTime = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        if (!greenPrint.getUUID().equals(cachedEntity) || gameTime - cachedAt >= 10) {
            RECURSIVE_REQUIREMENTS.clear();
            PRODUCER_RECIPES.clear();
            cachedEntity = greenPrint.getUUID();
            cachedAt = gameTime;
        }

        MISSING_COUNT_COLORS.clear();
        replaceOverlayIngredients(ingredients);
        for (Requirement requirement : requirements) {
            ChatFormatting color = missingCountColor(greenPrint, minecraft.player, requirement);
            ItemStack displayed = requirement.display().copyWithCount(requirement.count());
            ingredients.add(Pair.of(displayed, requirement.directlyAvailable()));
            if (color != null) {
                MISSING_COUNT_COLORS.put(displayed, color);
            }
        }
    }

    private static ChatFormatting missingCountColor(GreenPrintEntity greenPrint,
                                                    net.minecraft.world.entity.player.Player player,
                                                    Requirement requirement) {
        if (requirement.directlyAvailable()) {
            return null;
        }

        boolean hasRecipe = PRODUCER_RECIPES.computeIfAbsent(requirement.ingredient(),
                greenPrint::hasCraftingRecipe);
        if (!hasRecipe) {
            return ChatFormatting.RED;
        }

        Map<Integer, Boolean> byCount = RECURSIVE_REQUIREMENTS.computeIfAbsent(requirement.ingredient(),
                ignored -> new HashMap<>());
        boolean recursivelyAvailable = byCount.computeIfAbsent(requirement.count(), count ->
                greenPrint.canRecursivelyCraft(player, requirement.ingredient(), count));
        return recursivelyAvailable ? ChatFormatting.AQUA : ChatFormatting.GOLD;
    }

    /** Replaces only Create's count argument; all GUI drawing stays in Create's drawItemStack method. */
    public static String countText(ItemStack itemStack, String original) {
        ChatFormatting color = MISSING_COUNT_COLORS.get(itemStack);
        return color == null ? original : color + Integer.toString(itemStack.getCount());
    }

    /**
     * Create's overlay only sees the representative stack stored in a BlueprintSection.
     * Green Print keeps the real Ingredient, so allocate a copy of the inventory against
     * those ingredients to preserve tag alternatives and avoid counting one stack twice.
     */
    private static List<Requirement> requirementsFor(GreenPrintNode node, net.minecraft.world.entity.player.Player player) {
        List<GreenPrintIngredientGroup> groups = node.ingredientGroups();
        List<ItemStack> available = new ArrayList<>(player.getInventory().getContainerSize());
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            available.add(player.getInventory().getItem(slot).copy());
        }

        List<Integer> allocationOrder = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            allocationOrder.add(index);
        }
        allocationOrder.sort(Comparator.comparingInt(index -> groups.get(index).ingredient().getItems().length));

        boolean[] directlyAvailable = new boolean[groups.size()];
        for (int index : allocationOrder) {
            GreenPrintIngredientGroup group = groups.get(index);
            int remaining = group.count();
            for (ItemStack stack : available) {
                if (remaining == 0) {
                    break;
                }
                if (!stack.isEmpty() && group.ingredient().test(stack)) {
                    int used = Math.min(remaining, stack.getCount());
                    stack.shrink(used);
                    remaining -= used;
                }
            }
            directlyAvailable[index] = remaining == 0;
        }

        List<Requirement> requirements = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            GreenPrintIngredientGroup group = groups.get(index);
            requirements.add(new Requirement(group.ingredient(), displayStack(group.ingredient(), player), group.count(),
                    directlyAvailable[index]));
        }
        return requirements;
    }

    private static ItemStack displayStack(Ingredient ingredient, net.minecraft.world.entity.player.Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                return stack.copyWithCount(1);
            }
        }
        ItemStack[] choices = ingredient.getItems();
        return choices.length == 0 ? ItemStack.EMPTY : choices[0].copyWithCount(1);
    }

    private static void replaceOverlayIngredients(List<Pair<ItemStack, Boolean>> ingredients) {
        ingredients.clear();
    }

    @SuppressWarnings("unchecked")
    private static List<Pair<ItemStack, Boolean>> overlayIngredients() {
        if (INGREDIENTS_FIELD == null) {
            return null;
        }
        try {
            return (List<Pair<ItemStack, Boolean>>) INGREDIENTS_FIELD.get(null);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static void clearRenderState() {
        RECURSIVE_REQUIREMENTS.clear();
        PRODUCER_RECIPES.clear();
        MISSING_COUNT_COLORS.clear();
        cachedEntity = null;
        cachedAt = Long.MIN_VALUE;
    }

    private static Field overlayField(String name) {
        try {
            Field field = BlueprintOverlayRenderer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private record Requirement(Ingredient ingredient, ItemStack display, int count, boolean directlyAvailable) {
    }
}
