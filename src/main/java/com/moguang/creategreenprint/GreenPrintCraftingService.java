package com.moguang.creategreenprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/** Handles recursive crafting and the short-lived recipe index for a connected component. */
final class GreenPrintCraftingService {
    private static final int MAX_RECIPE_DEPTH = 64;
    private static final long RECIPE_INDEX_CACHE_TICKS = 20;

    private final GreenPrintEntity owner;
    private RecipeSearchIndex cachedRecipeIndex;
    private long cachedRecipeIndexAt = Long.MIN_VALUE;

    GreenPrintCraftingService(GreenPrintEntity owner) {
        this.owner = owner;
    }

    void invalidateConnectedRecipeCaches() {
        cachedRecipeIndex = null;
        cachedRecipeIndexAt = Long.MIN_VALUE;
        if (owner.level() == null) {
            return;
        }

        ArrayDeque<GreenPrintEntity> pending = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            GreenPrintEntity current = pending.removeFirst();
            if (!visited.add(current.getUUID())) {
                continue;
            }
            current.clearRecipeIndexCache();
            for (GreenPrintEntity candidate : owner.level().getEntitiesOfClass(GreenPrintEntity.class,
                    current.getBoundingBox().inflate(2.1))) {
                if (!visited.contains(candidate.getUUID()) && current.isAdjacentTo(candidate)) {
                    pending.addLast(candidate);
                }
            }
        }
    }

    void invalidateRecipeIndexCache() {
        cachedRecipeIndex = null;
        cachedRecipeIndexAt = Long.MIN_VALUE;
    }

    int craftOnce(Player player, GreenPrintNode node) {
        RecipeSearchIndex recipes = connectedRecipeIndex();
        RecipeRef root = new RecipeRef(owner, node);
        CraftTransaction transaction = resolveRecipe(player, root, recipes,
                new CraftTransaction(player.getInventory().getContainerSize()), Set.of(root.key()), 0, List.of(),
                new int[player.getInventory().getContainerSize()]);
        if (transaction == null) {
            return 0;
        }

        transaction.commit(player);
        ItemStack result = node.output().copy();
        int craftedCount = result.getCount();
        result.onCraftedBy(owner.level(), player, craftedCount);
        player.getInventory().placeItemBackInInventory(result);
        return craftedCount;
    }

    /** Runs the same reservation search as crafting without changing the inventory. */
    boolean canRecursivelyCraft(Player player, ItemStack requirement) {
        if (player == null || requirement.isEmpty()) {
            return false;
        }

        return canRecursivelyCraft(player, Ingredient.of(requirement.copyWithCount(1)));
    }

    /** Tests a full ingredient, including every alternative in a tag-based recipe input. */
    boolean canRecursivelyCraft(Player player, Ingredient requested) {
        return canRecursivelyCraft(player, requested, 1);
    }

    /**
     * Tests whether the requested quantity can be supplied by inventory and recursive
     * Green Print recipes. The quantity must be checked as a whole; checking one unit
     * would incorrectly mark a partially craftable group as fully available.
     */
    boolean canRecursivelyCraft(Player player, Ingredient requested, int count) {
        if (player == null || requested.isEmpty()) {
            return false;
        }
        if (count <= 0) {
            return true;
        }

        RecipeSearchIndex recipes = connectedRecipeIndex();
        List<Integer> requestedSlots = new ArrayList<>(count);
        for (int slot = 0; slot < count; slot++) {
            requestedSlots.add(slot);
        }

        CraftTransaction transaction = new CraftTransaction(player.getInventory().getContainerSize());
        int[] borrowedInventory = new int[player.getInventory().getContainerSize()];
        List<ConsumedInput> supplied = transaction.consumeFromInventory(player, requested, requestedSlots,
                borrowedInventory);
        if (supplied.size() == count) {
            return true;
        }

        List<Integer> missingSlots = requestedSlots.subList(supplied.size(), requestedSlots.size());
        GroupSupply crafted = craftMissingInputs(player, requested, missingSlots, recipes, transaction, Set.of());
        return crafted != null;
    }

    /** Returns whether this connected Green Print component has a producer for an ingredient. */
    boolean hasCraftingRecipe(Ingredient requested) {
        return requested != null && !requested.isEmpty()
                && !connectedRecipeIndex().candidatesFor(requested).isEmpty();
    }

    private CraftTransaction resolveRecipe(Player player, RecipeRef recipe, RecipeSearchIndex recipes,
                                           CraftTransaction transaction, Set<String> activeRecipes,
                                           int groupIndex, List<ConsumedInput> consumedInputs,
                                           int[] borrowedInventory) {
        List<GreenPrintIngredientGroup> groups = recipe.node().ingredientGroups();
        if (groupIndex >= groups.size()) {
            return addRecipeRemainders(recipe, consumedInputs, transaction, recipes,
                    nonConsumingIngredientSlots(recipe, recipes));
        }

        GreenPrintIngredientGroup group = groups.get(groupIndex);
        return resolveIngredientGroup(player, recipe, group, recipes, transaction, activeRecipes,
                groupIndex, consumedInputs, nonConsumingIngredientSlots(recipe, recipes), borrowedInventory);
    }

    private CraftTransaction resolveIngredientGroup(Player player, RecipeRef recipe,
                                                    GreenPrintIngredientGroup group,
                                                    RecipeSearchIndex recipes, CraftTransaction transaction,
                                                    Set<String> activeRecipes, int groupIndex,
                                                    List<ConsumedInput> consumedInputs,
                                                    Set<Integer> nonConsumingSlots, int[] borrowedInventory) {
        Ingredient ingredient = group.ingredient();
        CraftTransaction branch = transaction.copy();
        int[] borrowedInRecipe = borrowedInventory.clone();
        List<ConsumedInput> supplied = new ArrayList<>(group.count());
        List<Integer> borrowedSlots = group.slots().stream()
                .filter(nonConsumingSlots::contains)
                .toList();
        if (!borrowedSlots.isEmpty()) {
            List<ConsumedInput> borrowed = branch.borrowFromInventory(player, ingredient, borrowedSlots,
                    borrowedInRecipe);
            if (borrowed.size() != borrowedSlots.size()) {
                return null;
            }
            supplied.addAll(borrowed);
        }

        List<Integer> consumingSlots = group.slots().stream()
                .filter(slot -> !nonConsumingSlots.contains(slot))
                .toList();
        if (!consumingSlots.isEmpty()) {
            // Ingredients in one group are identical, so allocate all requested
            // units before considering a producer recipe for the remaining gap.
            List<ConsumedInput> consumedFromInventory = branch.consumeFromInventory(player, ingredient,
                    consumingSlots, borrowedInRecipe);
            supplied.addAll(consumedFromInventory);
            List<Integer> missingSlots = consumingSlots.subList(consumedFromInventory.size(), consumingSlots.size());
            if (!missingSlots.isEmpty()) {
                List<ConsumedInput> virtualInputs = branch.consumeVirtual(ingredient, missingSlots);
                supplied.addAll(virtualInputs);
                missingSlots = missingSlots.subList(virtualInputs.size(), missingSlots.size());
            }
            if (!missingSlots.isEmpty()) {
                GroupSupply craftedInputs = craftMissingInputs(player, ingredient, missingSlots, recipes, branch,
                        activeRecipes);
                if (craftedInputs == null) {
                    return null;
                }
                branch = craftedInputs.transaction();
                supplied.addAll(craftedInputs.inputs());
            }
        }

        return resolveRecipe(player, recipe, recipes, branch, activeRecipes, groupIndex + 1,
                appendConsumedInputs(consumedInputs, supplied), borrowedInRecipe);
    }

    /** Fills a group's missing quantity from one or more producer recipes. */
    private GroupSupply craftMissingInputs(Player player, Ingredient ingredient, List<Integer> missingSlots,
                                           RecipeSearchIndex recipes, CraftTransaction transaction,
                                           Set<String> activeRecipes) {
        return craftMissingInputs(player, ingredient, missingSlots, recipes.candidatesFor(ingredient), 0,
                recipes, transaction, activeRecipes);
    }

    /** Searches producer combinations by remaining group quantity, never by grid slot. */
    private GroupSupply craftMissingInputs(Player player, Ingredient ingredient, List<Integer> missingSlots,
                                           List<RecipeRef> candidates, int candidateIndex,
                                           RecipeSearchIndex recipes, CraftTransaction transaction,
                                           Set<String> activeRecipes) {
        if (missingSlots.isEmpty()) {
            return new GroupSupply(transaction, List.of());
        }
        if (candidateIndex >= candidates.size()) {
            return null;
        }

        RecipeRef dependency = candidates.get(candidateIndex);
        if (!ingredient.test(dependency.node().output()) || activeRecipes.contains(dependency.key())) {
            return craftMissingInputs(player, ingredient, missingSlots, candidates, candidateIndex + 1,
                    recipes, transaction, activeRecipes);
        }

        Set<String> nextActive = new HashSet<>(activeRecipes);
        nextActive.add(dependency.key());
        if (nextActive.size() <= MAX_RECIPE_DEPTH) {
            CraftTransaction dependencyResult = resolveRecipe(player, dependency, recipes, transaction.copy(),
                    nextActive, 0, List.of(), new int[player.getInventory().getContainerSize()]);
            if (dependencyResult != null) {
                dependencyResult.produce(dependency.node().output());
                int suppliedCount = Math.min(missingSlots.size(), dependency.node().output().getCount());
                List<Integer> suppliedSlots = missingSlots.subList(0, suppliedCount);
                List<ConsumedInput> supplied = dependencyResult.consumeVirtual(ingredient, suppliedSlots);
                if (supplied.size() == suppliedSlots.size()) {
                    GroupSupply remaining = craftMissingInputs(player, ingredient,
                            missingSlots.subList(suppliedCount, missingSlots.size()), candidates, candidateIndex,
                            recipes, dependencyResult, activeRecipes);
                    if (remaining != null) {
                        return new GroupSupply(remaining.transaction(), appendConsumedInputs(supplied, remaining.inputs()));
                    }
                }
            }
        }

        // The current producer cannot complete the remaining quantity. Try the next one
        // from the unmodified transaction so this branch remains fully backtrackable.
        return craftMissingInputs(player, ingredient, missingSlots, candidates, candidateIndex + 1,
                recipes, transaction, activeRecipes);
    }

    private CraftTransaction addRecipeRemainders(RecipeRef recipeRef, List<ConsumedInput> consumedInputs,
                                                 CraftTransaction transaction, RecipeSearchIndex recipes,
                                                 Set<Integer> nonConsumingSlots) {
        GreenPrintNode node = recipeRef.node();
        CraftingInput input = toCraftingInput(consumedInputs, transaction);
        if (input != null) {
            CraftingRecipe recipe = matchingCraftingRecipe(node, input, recipes);
            if (recipe != null) {
                List<ItemStack> remainders = recipe.getRemainingItems(input);
                List<Ingredient> ingredientGrid = recipes.ingredientGrid(node);
                Map<Integer, ConsumedInput> consumedBySlot = new HashMap<>();
                for (ConsumedInput consumedInput : consumedInputs) {
                    consumedBySlot.put(consumedInput.recipeSlot(), consumedInput);
                }
                for (int slot = 0; slot < remainders.size(); slot++) {
                    if (slot >= ingredientGrid.size() || ingredientGrid.get(slot).isEmpty()) {
                        continue;
                    }
                    ConsumedInput supplied = consumedBySlot.get(slot);
                    if (supplied == null) {
                        continue;
                    }
                    ItemStack remainder = remainders.get(slot);
                    if (nonConsumingSlots.contains(slot)) {
                        if (supplied.inventorySlot() >= 0) {
                            transaction.updateReusableInput(supplied.inventorySlot(), remainder);
                        }
                    } else if (!remainder.isEmpty()) {
                        if (supplied.inventorySlot() >= 0 && isDurabilityRemainder(supplied.stack(), remainder)) {
                            transaction.returnToInventory(supplied.inventorySlot(), remainder);
                        } else {
                            transaction.produce(remainder);
                        }
                    }
                }
                return transaction;
            }
        }

        // A manually edited section may no longer match its stored recipe.
        for (ConsumedInput consumedInput : consumedInputs) {
            if (consumedInput.stack().hasCraftingRemainingItem()) {
                transaction.produce(consumedInput.stack().getCraftingRemainingItem());
            }
        }
        return transaction;
    }

    private Set<Integer> nonConsumingIngredientSlots(RecipeRef recipeRef, RecipeSearchIndex recipes) {
        Set<Integer> cached = recipes.nonConsumingSlots(recipeRef);
        if (cached != null) {
            return cached;
        }

        List<ItemStack> sampleGrid = representativeInputGrid(recipeRef.node());
        if (sampleGrid == null) {
            return recipes.cacheNonConsumingSlots(recipeRef, Set.of());
        }
        CraftingRecipe recipe = matchingCraftingRecipe(recipeRef.node(), CraftingInput.of(3, 3, sampleGrid), recipes);
        if (recipe == null) {
            return recipes.cacheNonConsumingSlots(recipeRef, Set.of());
        }

        Set<Integer> slots = new HashSet<>();
        List<ItemStack> remainders = recipe.getRemainingItems(CraftingInput.of(3, 3, sampleGrid));
        List<Ingredient> ingredientGrid = recipes.ingredientGrid(recipeRef.node());
        for (int slot = 0; slot < ingredientGrid.size() && slot < remainders.size(); slot++) {
            ItemStack supplied = sampleGrid.get(slot);
            ItemStack remainder = remainders.get(slot);
            if (!supplied.isEmpty() && remainder.getCount() == supplied.getCount()
                    && supplied.getItem() == remainder.getItem()) {
                slots.add(slot);
            }
        }
        return recipes.cacheNonConsumingSlots(recipeRef, Set.copyOf(slots));
    }

    private static List<ItemStack> representativeInputGrid(GreenPrintNode node) {
        List<Ingredient> ingredientGrid = node.ingredientGrid();
        if (ingredientGrid.size() > 9) {
            return null;
        }
        List<ItemStack> grid = new ArrayList<>(List.of(
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
        for (int slot = 0; slot < ingredientGrid.size(); slot++) {
            Ingredient ingredient = ingredientGrid.get(slot);
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] choices = ingredient.getItems();
            if (choices.length == 0 || choices[0].isEmpty()) {
                return null;
            }
            grid.set(slot, choices[0].copyWithCount(1));
        }
        return grid;
    }

    private CraftingRecipe matchingCraftingRecipe(GreenPrintNode node, CraftingInput input,
                                                  RecipeSearchIndex recipes) {
        CraftingRecipe storedRecipe = recipes.storedRecipe(owner.level(), node.id());
        if (matchesNodeOutput(storedRecipe, node, input)) {
            return storedRecipe;
        }

        CraftingRecipe cachedDiscoveredRecipe = recipes.discoveredRecipe(node);
        if (matchesNodeOutput(cachedDiscoveredRecipe, node, input)) {
            return cachedDiscoveredRecipe;
        }
        CraftingRecipe discoveredRecipe = owner.level().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, owner.level())
                .map(holder -> holder.value())
                .orElse(null);
        if (!matchesNodeOutput(discoveredRecipe, node, input)) {
            return null;
        }
        recipes.cacheDiscoveredRecipe(node, discoveredRecipe);
        return discoveredRecipe;
    }

    private boolean matchesNodeOutput(CraftingRecipe recipe, GreenPrintNode node, CraftingInput input) {
        if (recipe == null || !recipe.matches(input, owner.level())) {
            return false;
        }
        ItemStack result = recipe.assemble(input, owner.level().registryAccess());
        return ItemStack.isSameItemSameComponents(result, node.output())
                && result.getCount() == node.output().getCount();
    }

    private static CraftingInput toCraftingInput(List<ConsumedInput> consumedInputs,
                                                 CraftTransaction transaction) {
        List<ItemStack> grid = new ArrayList<>(List.of(
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
        for (ConsumedInput consumedInput : consumedInputs) {
            int slot = consumedInput.recipeSlot();
            if (slot < 0 || slot >= grid.size() || !grid.get(slot).isEmpty()) {
                return null;
            }
            grid.set(slot, transaction.currentReusableInput(consumedInput).copyWithCount(1));
        }
        return CraftingInput.of(3, 3, grid);
    }

    private static List<ConsumedInput> appendConsumedInputs(List<ConsumedInput> consumedInputs,
                                                             List<ConsumedInput> additions) {
        List<ConsumedInput> inputs = new ArrayList<>(consumedInputs.size() + additions.size());
        inputs.addAll(consumedInputs);
        inputs.addAll(additions);
        return inputs;
    }

    private static boolean isDurabilityRemainder(ItemStack supplied, ItemStack remainder) {
        return supplied.isDamageableItem() && !remainder.isEmpty()
                && supplied.getItem() == remainder.getItem();
    }

    private RecipeSearchIndex connectedRecipeIndex() {
        long gameTime = owner.level().getGameTime();
        if (cachedRecipeIndex != null && gameTime - cachedRecipeIndexAt <= RECIPE_INDEX_CACHE_TICKS) {
            return cachedRecipeIndex;
        }

        List<RecipeRef> recipes = new ArrayList<>();
        for (GreenPrintEntity entity : owner.connectedEntities()) {
            for (GreenPrintNode node : entity.graph().nodes()) {
                recipes.add(new RecipeRef(entity, node));
            }
        }
        cachedRecipeIndex = new RecipeSearchIndex(recipes);
        cachedRecipeIndexAt = gameTime;
        return cachedRecipeIndex;
    }

    private record RecipeRef(GreenPrintEntity entity, GreenPrintNode node) {
        private String key() {
            return entity.getUUID() + ":" + node.id();
        }
    }

    private static final class RecipeSearchIndex {
        private final List<RecipeRef> recipes;
        private final Map<Item, List<RecipeRef>> recipesByOutputItem = new HashMap<>();
        private final Map<Ingredient, List<RecipeRef>> candidatesByIngredient = new IdentityHashMap<>();
        private final Map<ResourceLocation, CraftingRecipe> storedRecipes = new HashMap<>();
        private final Set<ResourceLocation> missingStoredRecipes = new HashSet<>();
        private final Map<GreenPrintNode, CraftingRecipe> discoveredRecipes = new IdentityHashMap<>();
        private final Map<String, Set<Integer>> nonConsumingSlots = new HashMap<>();
        private final Map<GreenPrintNode, List<Ingredient>> ingredientGrids = new IdentityHashMap<>();

        private RecipeSearchIndex(List<RecipeRef> recipes) {
            this.recipes = List.copyOf(recipes);
            for (RecipeRef recipe : this.recipes) {
                ItemStack output = recipe.node().output();
                if (!output.isEmpty()) {
                    recipesByOutputItem.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(recipe);
                }
            }
        }

        private List<RecipeRef> candidatesFor(Ingredient ingredient) {
            return candidatesByIngredient.computeIfAbsent(ingredient, this::findCandidates);
        }

        private List<RecipeRef> findCandidates(Ingredient ingredient) {
            List<RecipeRef> candidates = new ArrayList<>();
            Set<RecipeRef> seen = new HashSet<>();
            ItemStack[] matchingItems = ingredient.getItems();
            if (matchingItems.length > 0) {
                for (ItemStack matchingItem : matchingItems) {
                    for (RecipeRef recipe : recipesByOutputItem.getOrDefault(matchingItem.getItem(), List.of())) {
                        if (seen.add(recipe) && ingredient.test(recipe.node().output())) {
                            candidates.add(recipe);
                        }
                    }
                }
            } else {
                for (RecipeRef recipe : recipes) {
                    if (ingredient.test(recipe.node().output())) {
                        candidates.add(recipe);
                    }
                }
            }
            return List.copyOf(candidates);
        }

        private CraftingRecipe storedRecipe(Level level, ResourceLocation id) {
            if (missingStoredRecipes.contains(id)) {
                return null;
            }
            CraftingRecipe cached = storedRecipes.get(id);
            if (cached != null) {
                return cached;
            }
            CraftingRecipe loaded = level.getRecipeManager().byKey(id)
                    .map(holder -> holder.value())
                    .filter(CraftingRecipe.class::isInstance)
                    .map(CraftingRecipe.class::cast)
                    .orElse(null);
            if (loaded == null) {
                missingStoredRecipes.add(id);
            } else {
                storedRecipes.put(id, loaded);
            }
            return loaded;
        }

        private CraftingRecipe discoveredRecipe(GreenPrintNode node) {
            return discoveredRecipes.get(node);
        }

        private void cacheDiscoveredRecipe(GreenPrintNode node, CraftingRecipe recipe) {
            discoveredRecipes.put(node, recipe);
        }

        private Set<Integer> nonConsumingSlots(RecipeRef recipe) {
            return nonConsumingSlots.get(recipe.key());
        }

        private Set<Integer> cacheNonConsumingSlots(RecipeRef recipe, Set<Integer> slots) {
            nonConsumingSlots.put(recipe.key(), slots);
            return slots;
        }

        private List<Ingredient> ingredientGrid(GreenPrintNode node) {
            return ingredientGrids.computeIfAbsent(node, GreenPrintNode::ingredientGrid);
        }
    }

    private record GroupSupply(CraftTransaction transaction, List<ConsumedInput> inputs) {
    }

    private record ConsumedInput(ItemStack stack, int inventorySlot, int recipeSlot) {
    }

    private static final class CraftTransaction {
        private final int[] reservedInventory;
        private final List<ItemStack> virtualItems;
        private final Map<Integer, ItemStack> inventoryRemainders;
        /** Current state of a one-item reusable input taken from each inventory slot. */
        private final Map<Integer, ItemStack> reusableInventory;

        private CraftTransaction(int inventorySize) {
            reservedInventory = new int[inventorySize];
            virtualItems = new ArrayList<>();
            inventoryRemainders = new HashMap<>();
            reusableInventory = new HashMap<>();
        }

        private CraftTransaction(int[] reservedInventory, List<ItemStack> virtualItems,
                                 Map<Integer, ItemStack> inventoryRemainders,
                                 Map<Integer, ItemStack> reusableInventory) {
            this.reservedInventory = reservedInventory;
            this.virtualItems = virtualItems;
            this.inventoryRemainders = inventoryRemainders;
            this.reusableInventory = reusableInventory;
        }

        private CraftTransaction copy() {
            List<ItemStack> copiedItems = new ArrayList<>(virtualItems.size());
            for (ItemStack stack : virtualItems) {
                copiedItems.add(stack.copy());
            }
            Map<Integer, ItemStack> copiedRemainders = new HashMap<>();
            for (Map.Entry<Integer, ItemStack> entry : inventoryRemainders.entrySet()) {
                copiedRemainders.put(entry.getKey(), entry.getValue().copy());
            }
            Map<Integer, ItemStack> copiedReusableInputs = new HashMap<>();
            for (Map.Entry<Integer, ItemStack> entry : reusableInventory.entrySet()) {
                copiedReusableInputs.put(entry.getKey(), entry.getValue().copy());
            }
            return new CraftTransaction(reservedInventory.clone(), copiedItems, copiedRemainders,
                    copiedReusableInputs);
        }

        private List<ConsumedInput> consumeFromInventory(Player player, Ingredient ingredient,
                                                         List<Integer> recipeSlots, int[] borrowedInventory) {
            List<ConsumedInput> consumed = new ArrayList<>(recipeSlots.size());
            for (int recipeSlot : recipeSlots) {
                for (int inventorySlot = 0; inventorySlot < reservedInventory.length; inventorySlot++) {
                    if (reusableInventory.containsKey(inventorySlot)) {
                        continue;
                    }
                    ItemStack stack = player.getInventory().getItem(inventorySlot);
                    if (stack.getCount() > reservedInventory[inventorySlot] + borrowedInventory[inventorySlot]
                            && ingredient.test(stack)) {
                        reservedInventory[inventorySlot]++;
                        consumed.add(new ConsumedInput(stack.copyWithCount(1), inventorySlot, recipeSlot));
                        break;
                    }
                }
            }
            return consumed;
        }

        private List<ConsumedInput> borrowFromInventory(Player player, Ingredient ingredient,
                                                        List<Integer> recipeSlots, int[] borrowedInventory) {
            List<ConsumedInput> borrowed = new ArrayList<>(recipeSlots.size());
            for (int recipeSlot : recipeSlots) {
                for (int inventorySlot = 0; inventorySlot < borrowedInventory.length; inventorySlot++) {
                    ItemStack inventoryStack = player.getInventory().getItem(inventorySlot);
                    ItemStack stack = reusableInventory.getOrDefault(inventorySlot, inventoryStack);
                    if (inventoryStack.getCount() > reservedInventory[inventorySlot] + borrowedInventory[inventorySlot]
                            && ingredient.test(stack)) {
                        borrowedInventory[inventorySlot]++;
                        borrowed.add(new ConsumedInput(stack.copyWithCount(1), inventorySlot, recipeSlot));
                        break;
                    }
                }
            }
            return borrowed;
        }

        private List<ConsumedInput> consumeVirtual(Ingredient ingredient, List<Integer> recipeSlots) {
            List<ConsumedInput> consumed = new ArrayList<>(recipeSlots.size());
            for (int recipeSlot : recipeSlots) {
                boolean found = false;
                for (int index = 0; index < virtualItems.size(); index++) {
                    ItemStack stack = virtualItems.get(index);
                    if (!stack.isEmpty() && ingredient.test(stack)) {
                        ItemStack input = stack.copyWithCount(1);
                        stack.shrink(1);
                        if (stack.isEmpty()) {
                            virtualItems.remove(index);
                        }
                        consumed.add(new ConsumedInput(input, -1, recipeSlot));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    break;
                }
            }
            return consumed;
        }

        private void produce(ItemStack stack) {
            virtualItems.add(stack.copy());
        }

        private void returnToInventory(int slot, ItemStack remainder) {
            inventoryRemainders.put(slot, remainder.copy());
        }

        private ItemStack currentReusableInput(ConsumedInput supplied) {
            if (supplied.inventorySlot() < 0) {
                return supplied.stack();
            }
            return reusableInventory.getOrDefault(supplied.inventorySlot(), supplied.stack());
        }

        private void updateReusableInput(int slot, ItemStack remainder) {
            reusableInventory.put(slot, remainder.copyWithCount(remainder.isEmpty() ? 0 : 1));
        }

        private void commit(Player player) {
            for (int slot = 0; slot < reservedInventory.length; slot++) {
                if (reservedInventory[slot] > 0) {
                    player.getInventory().removeItem(slot, reservedInventory[slot]);
                }
            }
            for (Map.Entry<Integer, ItemStack> entry : inventoryRemainders.entrySet()) {
                int slot = entry.getKey();
                ItemStack remainder = entry.getValue().copy();
                ItemStack current = player.getInventory().getItem(slot);
                if (current.isEmpty()) {
                    player.getInventory().setItem(slot, remainder);
                } else if (ItemStack.isSameItemSameComponents(current, remainder)
                        && current.getCount() + remainder.getCount() <= current.getMaxStackSize()) {
                    current.grow(remainder.getCount());
                } else {
                    player.getInventory().placeItemBackInInventory(remainder);
                }
            }
            for (Map.Entry<Integer, ItemStack> entry : reusableInventory.entrySet()) {
                int slot = entry.getKey();
                ItemStack reusable = entry.getValue();
                ItemStack current = player.getInventory().getItem(slot);
                if (current.isEmpty()) {
                    if (!reusable.isEmpty()) {
                        player.getInventory().setItem(slot, reusable.copy());
                    }
                    continue;
                }
                if (reusable.isEmpty()) {
                    current.shrink(1);
                } else if (!ItemStack.isSameItemSameComponents(current, reusable)) {
                    ItemStack untouched = current.copy();
                    untouched.shrink(1);
                    player.getInventory().setItem(slot, reusable.copy());
                    if (!untouched.isEmpty()) {
                        player.getInventory().placeItemBackInInventory(untouched);
                    }
                }
            }
            for (ItemStack stack : virtualItems) {
                if (!stack.isEmpty()) {
                    stack.onCraftedBy(player.level(), player, stack.getCount());
                    player.getInventory().placeItemBackInInventory(stack.copy());
                }
            }
        }
    }
}
