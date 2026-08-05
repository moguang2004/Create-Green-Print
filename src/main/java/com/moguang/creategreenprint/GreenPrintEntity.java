package com.moguang.creategreenprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.AllItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.simibubi.create.content.equipment.blueprint.BlueprintEntity;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.util.Mth;

/** A wall-mounted Create-style editor host with an unbounded Green Print graph. */
public class GreenPrintEntity extends BlueprintEntity {
    private static final String GREEN_PRINT_GRAPH = "GreenPrintGraph";
    private final GreenPrintCraftingService craftingService = new GreenPrintCraftingService(this);
    private GreenPrintGraph cachedGraph;
    private long cachedGraphFingerprint = Long.MIN_VALUE;
    private boolean graphDirty = true;
    private boolean normalizedFilterInputs;
    private int boundsWidth = 1;
    private int boundsHeight = 1;
    private List<RenderTile> cachedRenderTiles = List.of();
    private long cachedRenderFingerprint = Long.MIN_VALUE;
    private int renderWidth = 1;
    private int renderHeight = 1;
    private boolean renderTilesDirty = true;

    static final int CONNECTION_LEFT = 1;
    static final int CONNECTION_RIGHT = 2;
    static final int CONNECTION_UP = 4;
    static final int CONNECTION_DOWN = 8;
    static final int CONNECTION_TOP_LEFT = 16;
    static final int CONNECTION_TOP_RIGHT = 32;
    static final int CONNECTION_BOTTOM_LEFT = 64;
    static final int CONNECTION_BOTTOM_RIGHT = 128;

    public GreenPrintEntity(EntityType<GreenPrintEntity> type, Level level) {
        super(type, level);
    }

    public GreenPrintEntity(Level level, BlockPos pos, Direction direction, Direction verticalOrientation) {
        this(CreateGreenPrint.GREEN_PRINT_ENTITY.get(), level);
        this.verticalOrientation = verticalOrientation;
        updateFacingWithBoundingBox(direction, verticalOrientation);
        setPos(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        boundsWidth = Math.max(1, size);
        boundsHeight = Math.max(1, size);
        invalidateGraphCaches();
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        super.readSpawnData(buffer);
        invalidateGraphCaches();
    }

    @Override
    public void onPersistentDataUpdated() {
        super.onPersistentDataUpdated();
        invalidateGraphCaches();
        invalidateConnectedRecipeCaches();
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        invalidateGraphCaches();
        invalidateConnectedRecipeCaches();
    }

    @Override
    public void onRemovedFromLevel() {
        invalidateConnectedRecipeCaches();
        super.onRemovedFromLevel();
    }

    public GreenPrintGraph graph() {
        long recipeFingerprint = recipeFingerprint();
        if (!graphDirty && cachedGraph != null && cachedGraphFingerprint == recipeFingerprint) {
            return cachedGraph;
        }
        if (cachedGraph != null && cachedGraphFingerprint != recipeFingerprint) {
            invalidateConnectedRecipeCaches();
        }
        graphDirty = true;

        CompoundTag recipe = getOrCreateRecipeCompound();
        GreenPrintGraph stored = recipe.contains(GREEN_PRINT_GRAPH, CompoundTag.TAG_COMPOUND)
                ? GreenPrintGraph.load(level().registryAccess(), recipe.getCompound(GREEN_PRINT_GRAPH))
                : new GreenPrintGraph();
        normalizedFilterInputs = false;
        GreenPrintGraph synchronizedGraph = synchronizeBlueprintSections(stored, level().registryAccess());
        boolean layoutChanged = !sameNodeLayout(stored, synchronizedGraph);
        int newWidth = Math.max(1, graphWidth(synchronizedGraph));
        int newHeight = Math.max(1, graphHeight(synchronizedGraph));
        int newSize = Math.max(newWidth, newHeight);
        boolean boundsChanged = boundsWidth != newWidth || boundsHeight != newHeight;
        boundsWidth = newWidth;
        boundsHeight = newHeight;

        // Create's section cache is indexed by the current square stride. Repack
        // the numeric Recipes keys whenever the graph shape changes so removed
        // sections cannot be reused for a different coordinate later.
        if (layoutChanged || normalizedFilterInputs) {
            recipe.put(GREEN_PRINT_GRAPH, synchronizedGraph.save(level().registryAccess()));
            size = newSize;
            writeBlueprintSections(level().registryAccess(), synchronizedGraph);
            onPersistentDataUpdated();
            recalculateBoundingBox();
        } else if (size != newSize || boundsChanged) {
            size = newSize;
            recalculateBoundingBox();
        }
        cachedGraph = synchronizedGraph;
        cachedGraphFingerprint = recipeFingerprint();
        graphDirty = false;
        renderTilesDirty = true;
        return synchronizedGraph;
    }

    public void setGraph(HolderLookup.Provider registries, GreenPrintGraph graph) {
        getOrCreateRecipeCompound().put(GREEN_PRINT_GRAPH, graph.save(registries));
        boundsWidth = Math.max(1, graphWidth(graph));
        boundsHeight = Math.max(1, graphHeight(graph));
        size = Math.max(boundsWidth, boundsHeight);
        writeBlueprintSections(registries, graph);
        onPersistentDataUpdated();
        cachedGraph = graph;
        cachedGraphFingerprint = recipeFingerprint();
        graphDirty = false;
        recalculateBoundingBox();
    }

    private void invalidateGraphCaches() {
        cachedGraph = null;
        cachedGraphFingerprint = Long.MIN_VALUE;
        graphDirty = true;
        cachedRenderTiles = List.of();
        cachedRenderFingerprint = Long.MIN_VALUE;
        renderWidth = 1;
        renderHeight = 1;
        renderTilesDirty = true;
    }

    void clearRecipeIndexCache() {
        craftingService.invalidateRecipeIndexCache();
    }

    /** Lightweight render data backed by Create's already cached BlueprintSections. */
    List<RenderTile> renderTiles() {
        long recipeFingerprint = recipeFingerprint();
        if (!renderTilesDirty && cachedRenderFingerprint == recipeFingerprint) {
            return cachedRenderTiles;
        }

        int stride = Math.max(1, size);
        List<RenderTile> tiles = new ArrayList<>();
        int maxX = -1;
        int maxY = -1;
        CompoundTag recipes = getOrCreateRecipeCompound();
        for (int index = 0; index < stride * stride; index++) {
            CompoundTag serialized = recipes.getCompound(Integer.toString(index));
            if (serialized.isEmpty()) {
                continue;
            }

            ItemStack[] slots = new ItemStack[10];
            ListTag serializedItems = serialized.getList("Items", CompoundTag.TAG_COMPOUND);
            for (int itemIndex = 0; itemIndex < serializedItems.size(); itemIndex++) {
                CompoundTag item = serializedItems.getCompound(itemIndex);
                int slot = item.getByte("Slot") & 0xff;
                if (slot < slots.length) {
                    slots[slot] = ItemStack.parseOptional(level().registryAccess(), item);
                }
            }

            boolean present = false;
            boolean hasInputs = false;
            for (int slot = 0; slot < 10; slot++) {
                ItemStack stack = slots[slot];
                if (stack != null && !stack.isEmpty()) {
                    present = true;
                    hasInputs |= slot < 9;
                    break;
                }
            }
            if (!present) {
                continue;
            }

            int x = index % stride;
            int y = index / stride;
            ItemStack output = slots[9] == null ? ItemStack.EMPTY : slots[9].copy();
            tiles.add(new RenderTile(x, y, output, hasInputs));
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        int newWidth = maxX < 0 ? 1 : maxX + 1;
        int newHeight = maxY < 0 ? 1 : maxY + 1;
        boolean boundsChanged = renderWidth != newWidth || renderHeight != newHeight;
        renderWidth = newWidth;
        renderHeight = newHeight;
        boundsWidth = newWidth;
        boundsHeight = newHeight;
        size = Math.max(size, Math.max(newWidth, newHeight));
        cachedRenderTiles = List.copyOf(tiles);
        cachedRenderFingerprint = recipeFingerprint;
        renderTilesDirty = false;
        if (boundsChanged) {
            recalculateBoundingBox();
        }
        return cachedRenderTiles;
    }

    /**
     * Create's GUI writes section data directly into the shared Recipes tag and
     * does not call the entity's persistent-data callback. Hashing that small
     * tag lets the lightweight caches notice a GUI edit without rebuilding the
     * recursive graph every render tick.
     */
    private long recipeFingerprint() {
        return getOrCreateRecipeCompound().hashCode();
    }

    int renderWidth() {
        renderTiles();
        return renderWidth;
    }

    int renderHeight() {
        renderTiles();
        return renderHeight;
    }

    record RenderTile(int x, int y, ItemStack output, boolean hasInputs) {
    }

    private void writeBlueprintSections(HolderLookup.Provider registries, GreenPrintGraph graph) {
        CompoundTag recipes = getOrCreateRecipeCompound();
        for (String key : List.copyOf(recipes.getAllKeys())) {
            if (key.chars().allMatch(Character::isDigit)) {
                recipes.remove(key);
            }
        }

        int minX = graph.nodes().stream().mapToInt(GreenPrintNode::x).min().orElse(0);
        int minY = graph.nodes().stream().mapToInt(GreenPrintNode::y).min().orElse(0);
        for (GreenPrintNode node : graph.nodes()) {
            int index = sectionIndex(node.x() - minX, node.y() - minY);
            ItemStackHandler items = new ItemStackHandler(11);
            List<Ingredient> ingredientGrid = node.ingredientGrid();
            for (int slot = 0; slot < Math.min(9, ingredientGrid.size()); slot++) {
                ItemStack[] choices = ingredientGrid.get(slot).getItems();
                if (choices.length > 0) {
                    items.setStackInSlot(slot, choices[0].copy());
                }
            }
            items.setStackInSlot(9, node.output().copy());
            recipes.put(Integer.toString(index), items.serializeNBT(registries));
        }
    }

    private GreenPrintGraph synchronizeBlueprintSections(GreenPrintGraph stored, HolderLookup.Provider registries) {
        CompoundTag recipes = getOrCreateRecipeCompound();
        List<GreenPrintNode> synchronizedNodes = new ArrayList<>();
        Set<Integer> usedSections = new HashSet<>();
        int minX = stored.nodes().stream().mapToInt(GreenPrintNode::x).min().orElse(0);
        int minY = stored.nodes().stream().mapToInt(GreenPrintNode::y).min().orElse(0);

        for (GreenPrintNode node : stored.nodes()) {
            int index = sectionIndex(node.x() - minX, node.y() - minY);
            usedSections.add(index);
            SectionData section = readSection(recipes, index, registries);
            if (!section.present()) {
                synchronizedNodes.add(node);
            } else if (!section.empty()) {
                List<Ingredient> ingredients = restoreTagIngredients(node, section);
                synchronizedNodes.add(new GreenPrintNode(node.id(), node.x(), node.y(),
                        GreenPrintIngredientGroup.compact(ingredients),
                        section.output().isEmpty() ? node.output() : section.output()));
            }
        }

        for (String key : recipes.getAllKeys()) {
            if (!key.chars().allMatch(Character::isDigit)) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (!usedSections.add(index)) {
                continue;
            }
            SectionData section = readSection(recipes, index, registries);
            if (section.empty()) {
                continue;
            }
            int x = minX + index % Math.max(1, size);
            int y = minY + index / Math.max(1, size);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CreateGreenPrint.MODID, "manual_section_" + index);
            synchronizedNodes.add(new GreenPrintNode(id, x, y,
                    GreenPrintIngredientGroup.compact(restoreTagIngredients(null, section)), section.output()));
        }
        return new GreenPrintGraph(synchronizedNodes);
    }

    private SectionData readSection(CompoundTag recipes, int index, HolderLookup.Provider registries) {
        CompoundTag serialized = recipes.getCompound(Integer.toString(index));
        if (serialized.isEmpty()) {
            return SectionData.EMPTY;
        }

        ItemStack[] slots = new ItemStack[11];
        ListTag serializedItems = serialized.getList("Items", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < serializedItems.size(); i++) {
            CompoundTag item = serializedItems.getCompound(i);
            int slot = item.getByte("Slot") & 0xff;
            if (slot < slots.length) {
                slots[slot] = ItemStack.parseOptional(registries, item);
            }
        }

        ItemStack output = slots[9] == null ? ItemStack.EMPTY : slots[9];
        List<Ingredient> ingredients = new ArrayList<>();
        List<ItemStack> concreteInputs = resolveFilterInputs(slots, output, registries);
        if (concreteInputs != null) {
            for (int slot = 0; slot < 9; slot++) {
                slots[slot] = concreteInputs.get(slot);
            }
            normalizedFilterInputs = true;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slots[slot] != null && !slots[slot].isEmpty()) {
                ingredients.add(ingredientForStoredStack(slots[slot]));
            } else {
                ingredients.add(Ingredient.EMPTY);
            }
        }
        return new SectionData(ingredients, output);
    }

    /** Keeps tag alternatives after the visible filter slot has been normalized to one item. */
    private List<Ingredient> restoreTagIngredients(GreenPrintNode storedNode, SectionData section) {
        List<Ingredient> current = section.ingredients();
        List<Ingredient> restored = new ArrayList<>(current);

        if (storedNode != null) {
            List<Ingredient> previousGrid = storedNode.ingredientGrid();
            for (int slot = 0; slot < Math.min(current.size(), previousGrid.size()); slot++) {
                Ingredient previous = previousGrid.get(slot);
                ItemStack representative = representativeStack(current.get(slot));
                if (previous.getItems().length > 1 && !representative.isEmpty()
                        && previous.test(representative)) {
                    restored.set(slot, previous);
                }
            }
        }

        List<Ingredient> recipeGrid = findMatchingRecipeGrid(section.output(), current);
        if (recipeGrid == null) {
            return restored;
        }

        for (int slot = 0; slot < Math.min(current.size(), recipeGrid.size()); slot++) {
            Ingredient expected = recipeGrid.get(slot);
            Ingredient existing = restored.get(slot);
            if (expected.getItems().length <= existing.getItems().length
                    || expected.getItems().length <= current.get(slot).getItems().length) {
                continue;
            }
            ItemStack representative = representativeStack(current.get(slot));
            if (!representative.isEmpty() && expected.test(representative)) {
                restored.set(slot, expected);
                normalizedFilterInputs = true;
            }
        }
        return restored;
    }

    /** Finds the crafting recipe whose output and currently displayed inputs agree. */
    private List<Ingredient> findMatchingRecipeGrid(ItemStack output, List<Ingredient> current) {
        if (output.isEmpty()) {
            return null;
        }
        List<ItemStack> currentGrid = representativeInputGrid(current);
        List<Ingredient> best = null;
        int bestTagSlots = 0;
        for (RecipeHolder<CraftingRecipe> holder : level().getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(level().registryAccess());
            if (!ItemStack.isSameItemSameComponents(result, output) || result.getCount() != output.getCount()) {
                continue;
            }
            List<Ingredient> recipeGrid = recipeIngredientGrid(recipe);
            if (recipeGrid == null || !matchesIngredientGrid(recipeGrid, currentGrid)) {
                continue;
            }
            int tagSlots = 0;
            for (int slot = 0; slot < recipeGrid.size(); slot++) {
                if (recipeGrid.get(slot).getItems().length > current.get(slot).getItems().length) {
                    tagSlots++;
                }
            }
            if (tagSlots > bestTagSlots) {
                best = recipeGrid;
                bestTagSlots = tagSlots;
            }
        }
        return bestTagSlots == 0 ? null : best;
    }

    private static boolean matchesIngredientGrid(List<Ingredient> expected, List<ItemStack> current) {
        for (int slot = 0; slot < expected.size() && slot < current.size(); slot++) {
            Ingredient ingredient = expected.get(slot);
            ItemStack stack = current.get(slot);
            if (ingredient.isEmpty() != stack.isEmpty()
                    || !ingredient.isEmpty() && !ingredient.test(stack)) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack representativeStack(Ingredient ingredient) {
        ItemStack[] choices = ingredient.getItems();
        return choices.length == 0 ? ItemStack.EMPTY : choices[0].copyWithCount(1);
    }

    /**
     * Damage is runtime state for a tool, not part of the crafting ingredient.
     * Keep other components exact so recipes that require a specific item variant
     * retain their normal matching behavior.
     */
    private static Ingredient ingredientForStoredStack(ItemStack stack) {
        return stack.isDamageableItem() ? Ingredient.of(stack.getItem()) : Ingredient.of(stack);
    }

    private static List<ItemStack> representativeInputGrid(List<Ingredient> ingredients) {
        if (ingredients.size() > 9) {
            return null;
        }
        List<ItemStack> grid = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            grid.add(ItemStack.EMPTY);
        }
        for (int slot = 0; slot < ingredients.size(); slot++) {
            Ingredient ingredient = ingredients.get(slot);
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

    /** Replaces JEI's Create filter stacks with concrete inputs from the chosen recipe. */
    private List<ItemStack> resolveFilterInputs(ItemStack[] slots, ItemStack output,
                                                HolderLookup.Provider registries) {
        boolean containsFilter = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack slotStack = slots[slot] == null ? ItemStack.EMPTY : slots[slot];
            if (FilterItemStack.of(slotStack).isFilterItem()) {
                containsFilter = true;
                break;
            }
        }
        if (!containsFilter || output.isEmpty()) {
            return null;
        }

        for (RecipeHolder<CraftingRecipe> holder : level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(registries);
            if (!ItemStack.isSameItemSameComponents(result, output) || result.getCount() != output.getCount()) {
                continue;
            }

            List<Ingredient> recipeGrid = recipeIngredientGrid(recipe);
            if (recipeGrid == null) {
                continue;
            }
            List<ItemStack> concreteInputs = new ArrayList<>(9);
            boolean matchesBlueprint = true;
            for (int slot = 0; slot < 9; slot++) {
                Ingredient expected = recipeGrid.get(slot);
                ItemStack slotStack = slots[slot] == null ? ItemStack.EMPTY : slots[slot];
                FilterItemStack requested = FilterItemStack.of(slotStack);
                if (expected.isEmpty()) {
                    if (!requested.isEmpty()) {
                        matchesBlueprint = false;
                        break;
                    }
                    concreteInputs.add(ItemStack.EMPTY);
                    continue;
                }

                ItemStack selected = ItemStack.EMPTY;
                for (ItemStack candidate : expected.getItems()) {
                    if (requested.test(level(), candidate)) {
                        selected = candidate.copyWithCount(1);
                        break;
                    }
                }
                if (selected.isEmpty()) {
                    matchesBlueprint = false;
                    break;
                }
                concreteInputs.add(selected);
            }
            if (matchesBlueprint) {
                return concreteInputs;
            }
        }
        return null;
    }

    private static List<Ingredient> recipeIngredientGrid(CraftingRecipe recipe) {
        List<Ingredient> grid = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            grid.add(Ingredient.EMPTY);
        }

        List<Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe shaped) {
            if (shaped.getWidth() > 3 || shaped.getHeight() > 3) {
                return null;
            }
            for (int row = 0; row < shaped.getHeight(); row++) {
                for (int column = 0; column < shaped.getWidth(); column++) {
                    grid.set(row * 3 + column, ingredients.get(row * shaped.getWidth() + column));
                }
            }
            return grid;
        }

        if (ingredients.size() > 9) {
            return null;
        }
        for (int slot = 0; slot < ingredients.size(); slot++) {
            grid.set(slot, ingredients.get(slot));
        }
        return grid;
    }

    private int sectionIndex(int x, int y) {
        return y * Math.max(1, size) + x;
    }

    private record SectionData(boolean present, List<Ingredient> ingredients, ItemStack output) {
        private static final SectionData EMPTY = new SectionData(false, List.of(), ItemStack.EMPTY);

        private SectionData(List<Ingredient> ingredients, ItemStack output) {
            this(true, ingredients, output);
        }

        private boolean empty() {
            return ingredients.stream().allMatch(Ingredient::isEmpty) && output.isEmpty();
        }
    }

    private static int graphWidth(GreenPrintGraph graph) {
        return graph.nodes().stream().mapToInt(GreenPrintNode::x).max().orElse(0)
                - graph.nodes().stream().mapToInt(GreenPrintNode::x).min().orElse(0) + 1;
    }

    private static int graphHeight(GreenPrintGraph graph) {
        return graph.nodes().stream().mapToInt(GreenPrintNode::y).max().orElse(0)
                - graph.nodes().stream().mapToInt(GreenPrintNode::y).min().orElse(0) + 1;
    }

    public int minGraphX() {
        return graph().nodes().stream().mapToInt(GreenPrintNode::x).min().orElse(0);
    }

    public int maxGraphX() {
        return graph().nodes().stream().mapToInt(GreenPrintNode::x).max().orElse(0);
    }

    public int minGraphY() {
        return graph().nodes().stream().mapToInt(GreenPrintNode::y).min().orElse(0);
    }

    public int maxGraphY() {
        return graph().nodes().stream().mapToInt(GreenPrintNode::y).max().orElse(0);
    }

    public int graphWidth() {
        return maxGraphX() - minGraphX() + 1;
    }

    public int graphHeight() {
        return maxGraphY() - minGraphY() + 1;
    }

    @Override
    public int getWidth() {
        return boundsWidth * 16;
    }

    @Override
    public int getHeight() {
        return boundsHeight * 16;
    }

    @Override
    public ItemStack getPickedResult(HitResult target) {
        return CreateGreenPrint.GREEN_PRINT.get().getDefaultInstance();
    }

    /** Returns whether the hovered section has crafting inputs for the client overlay. */
    public boolean hasRecipeAt(Vec3 hit) {
        renderTiles();
        int index = renderSectionIndexAt(hit);
        int stride = Math.max(1, size);
        int x = index % stride;
        int y = index / stride;
        for (RenderTile tile : cachedRenderTiles) {
            if (tile.x() == x && tile.y() == y) {
                return tile.hasInputs();
            }
        }
        return false;
    }

    @Override
    public InteractionResult interactAt(Player player, Vec3 hit, InteractionHand hand) {
        if (!canPlayerUse(player)) {
            return InteractionResult.FAIL;
        }
        renderTiles();
        int sectionIndex = renderSectionIndexAt(hit);
        GreenPrintNode node = null;
        RenderTile renderTile = renderTileAt(sectionIndex);

        if (!level().isClientSide && !isCreateWrench(player.getItemInHand(hand))
                && renderTile != null && !renderTile.output().isEmpty()) {
            GreenPrintGraph graph = graph();
            sectionIndex = sectionIndexAt(hit, graph);
            node = nodeAtSection(graph, sectionIndex);
        }

        if (!level().isClientSide && !isCreateWrench(player.getItemInHand(hand))
                && node != null && !node.output().isEmpty()) {
            int crafted = 0;
            do {
                int craftedThisPass = craftingService.craftOnce(player, node);
                if (craftedThisPass <= 0) {
                    break;
                }
                if (crafted == 0) {
                    level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
                            .2f, 1f + level().getRandom().nextFloat());
                }
                crafted += craftedThisPass;
            } while (player.isShiftKeyDown() && crafted < 64);
            return InteractionResult.SUCCESS;
        }

        if (!level().isClientSide) {
            int menuSectionIndex = sectionIndex;
            MenuProvider section = (MenuProvider) (Object) getSection(sectionIndex);
            player.openMenu(section, (RegistryFriendlyByteBuf buffer) -> {
                buffer.writeVarInt(getId());
                buffer.writeVarInt(menuSectionIndex);
            });
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    private RenderTile renderTileAt(int sectionIndex) {
        int stride = Math.max(1, size);
        int x = sectionIndex % stride;
        int y = sectionIndex / stride;
        for (RenderTile tile : cachedRenderTiles) {
            if (tile.x() == x && tile.y() == y) {
                return tile;
            }
        }
        return null;
    }

    private static boolean isCreateWrench(ItemStack stack) {
        return stack.is(AllItems.WRENCH.get());
    }

    private GreenPrintNode nodeAtSection(GreenPrintGraph graph, int sectionIndex) {
        int width = Math.max(1, graphWidth(graph));
        int x = sectionIndex % Math.max(1, size);
        int y = sectionIndex / Math.max(1, size);
        if (x < 0 || x >= width || y < 0 || y >= Math.max(1, graphHeight(graph))) {
            return null;
        }
        return graph.nodeAt(minGraphX() + x, minGraphY() + y);
    }

    private int renderSectionIndexAt(Vec3 hit) {
        int width = Math.max(1, renderWidth);
        int height = Math.max(1, renderHeight);
        if (width == 1 && height == 1) {
            return 0;
        }

        Vec3 local = VecHelper.rotate(hit, getYRot(), Direction.Axis.Y);
        local = VecHelper.rotate(local, -getXRot(), Direction.Axis.X);
        int x = Mth.floor(local.x + width / 2.0);
        int y = Mth.floor(local.y + height / 2.0);
        x = Mth.clamp(x, 0, width - 1);
        y = Mth.clamp(y, 0, height - 1);
        return y * Math.max(1, size) + x;
    }

    public boolean canRecursivelyCraft(Player player, ItemStack requirement) {
        return craftingService.canRecursivelyCraft(player, requirement);
    }

    boolean canRecursivelyCraft(Player player, Ingredient requirement) {
        return craftingService.canRecursivelyCraft(player, requirement);
    }

    boolean canRecursivelyCraft(Player player, Ingredient requirement, int count) {
        return craftingService.canRecursivelyCraft(player, requirement, count);
    }

    boolean hasCraftingRecipe(Ingredient requirement) {
        return craftingService.hasCraftingRecipe(requirement);
    }

    boolean hasEnoughToolDurability(Player player, GreenPrintNode node, GreenPrintIngredientGroup group) {
        return craftingService.hasEnoughToolDurability(player, node, group);
    }

    GreenPrintNode recipeNodeAt(Vec3 hit) {
        GreenPrintGraph graph = graph();
        return nodeAtSection(graph, sectionIndexAt(hit, graph));
    }

    /** Clears every recipe cache in the physical component after a placement or GUI edit. */
    private void invalidateConnectedRecipeCaches() {
        craftingService.invalidateConnectedRecipeCaches();
    }

    /** Collects the physical component, including chains of more than two entities. */
    List<GreenPrintEntity> connectedEntities() {
        ArrayDeque<GreenPrintEntity> pending = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        List<GreenPrintEntity> result = new ArrayList<>();
        pending.add(this);
        while (!pending.isEmpty()) {
            GreenPrintEntity current = pending.removeFirst();
            if (!visited.add(current.getUUID())) {
                continue;
            }
            // Loaded client entities may still have the default one-tile bounding box
            // until their graph has been synchronized.
            current.graph();
            result.add(current);
            for (GreenPrintEntity candidate : level().getEntitiesOfClass(GreenPrintEntity.class,
                    current.getBoundingBox().inflate(2.1))) {
                if (!visited.contains(candidate.getUUID()) && current.isAdjacentTo(candidate)) {
                    pending.addLast(candidate);
                }
            }
        }
        return result;
    }

    /** Two entities connect when their mounted planes match and their projected AABBs share an edge. */
    public boolean isAdjacentTo(GreenPrintEntity other) {
        if (other == this || getDirection() != other.getDirection()
                || verticalOrientation != other.verticalOrientation) {
            return false;
        }

        AABB first = getBoundingBox();
        AABB second = other.getBoundingBox();
        Direction.Axis normal = getDirection().getAxis();
        if (Math.abs(axisCenter(first, normal) - axisCenter(second, normal)) > 0.1) {
            return false;
        }

        Direction.Axis[] planeAxes = switch (normal) {
            case X -> new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Y};
        };
        boolean firstAxisConnection = edgeTouches(first, second, planeAxes[0])
                && overlaps(first, second, planeAxes[1]);
        boolean secondAxisConnection = edgeTouches(first, second, planeAxes[1])
                && overlaps(first, second, planeAxes[0]);
        return firstAxisConnection || secondAxisConnection;
    }

    /** Returns connection-texture bits for nodes that continue into a neighboring entity. */
    int externalConnectionMask(GreenPrintNode node) {
        GreenPrintGraph ownGraph = graph();
        double ownCenterX = (ownGraph.nodes().stream().mapToInt(GreenPrintNode::x).min().orElse(0)
                + ownGraph.nodes().stream().mapToInt(GreenPrintNode::x).max().orElse(0) + 1) / 2.0;
        double ownCenterY = (ownGraph.nodes().stream().mapToInt(GreenPrintNode::y).min().orElse(0)
                + ownGraph.nodes().stream().mapToInt(GreenPrintNode::y).max().orElse(0) + 1) / 2.0;
        return externalConnectionMask(nodeWorldCenter(node, ownCenterX, ownCenterY));
    }

    /** Connection bits for an entity that has no recipe tile yet. */
    int externalConnectionMask() {
        return externalConnectionMask(entityCenter());
    }

    /**
     * Projects every physical Green Print tile into this entity's local plane.
     * Empty and configured prints share this path so writing a recipe cannot
     * change how their common border is classified.
     */
    private int externalConnectionMask(Vec3 ownTileCenter) {
        Vec3 xAxis = renderXAxis();
        Vec3 yAxis = renderYAxis();
        int mask = 0;
        for (GreenPrintEntity other : level().getEntitiesOfClass(GreenPrintEntity.class,
                getBoundingBox().inflate(2.1))) {
            List<RenderTile> otherTiles = other.renderTiles();
            if (!isAdjacentTo(other)) {
                continue;
            }

            if (otherTiles.isEmpty()) {
                mask |= connectionBitTo(other.entityCenter(), ownTileCenter, xAxis, yAxis);
                continue;
            }

            double otherCenterX = other.renderWidth / 2.0;
            double otherCenterY = other.renderHeight / 2.0;
            for (RenderTile otherTile : otherTiles) {
                Vec3 otherTileCenter = other.renderTileWorldCenter(otherTile.x(), otherTile.y(),
                        otherCenterX, otherCenterY);
                mask |= connectionBitTo(otherTileCenter, ownTileCenter, xAxis, yAxis);
            }
        }
        return mask;
    }

    Map<Long, Integer> externalConnectionMasksForRender(List<RenderTile> localTiles,
                                                         int width, int height) {
        Map<Long, Integer> masks = new HashMap<>();
        List<RenderTile> tiles = localTiles.isEmpty()
                ? List.of(new RenderTile(0, 0, ItemStack.EMPTY, false))
                : localTiles;
        Vec3 xAxis = renderXAxis();
        Vec3 yAxis = renderYAxis();
        for (GreenPrintEntity other : level().getEntitiesOfClass(GreenPrintEntity.class,
                getBoundingBox().inflate(2.1))) {
            List<RenderTile> otherTiles = other.renderTiles();
            if (!isAdjacentTo(other)) {
                continue;
            }

            double otherCenterX = other.renderWidth / 2.0;
            double otherCenterY = other.renderHeight / 2.0;
            for (RenderTile tile : tiles) {
                Vec3 ownTileCenter = renderTileWorldCenter(tile.x(), tile.y(), width / 2.0, height / 2.0);
                int mask = 0;
                if (otherTiles.isEmpty()) {
                    mask = connectionBitTo(other.entityCenter(), ownTileCenter, xAxis, yAxis);
                } else {
                    for (RenderTile otherTile : otherTiles) {
                        Vec3 otherTileCenter = other.renderTileWorldCenter(otherTile.x(), otherTile.y(),
                                otherCenterX, otherCenterY);
                        mask |= connectionBitTo(otherTileCenter, ownTileCenter, xAxis, yAxis);
                    }
                }
                long key = renderPositionKey(tile.x(), tile.y());
                masks.merge(key, mask, (first, second) -> first | second);
            }
        }
        return masks;
    }

    static long renderPositionKey(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    int externalConnectionMaskForRender(int tileX, int tileY, int width, int height) {
        return externalConnectionMask(renderTileWorldCenter(tileX, tileY, width / 2.0, height / 2.0));
    }

    private Vec3 renderTileWorldCenter(int tileX, int tileY, double centerX, double centerY) {
        return entityCenter()
                .add(renderXAxis().scale(tileX + 0.5 - centerX))
                .add(renderYAxis().scale(tileY + 0.5 - centerY));
    }

    private int connectionBitTo(Vec3 otherTileCenter, Vec3 ownTileCenter, Vec3 xAxis, Vec3 yAxis) {
        Vec3 delta = otherTileCenter.subtract(ownTileCenter);
        double localX = delta.dot(xAxis);
        double localY = delta.dot(yAxis);
        int dx = projectedConnectionAxis(localX);
        int dy = projectedConnectionAxis(localY);
        return dx == Integer.MIN_VALUE || dy == Integer.MIN_VALUE || dx == 0 && dy == 0
                ? 0
                : connectionBit(dx, dy);
    }

    private static int projectedConnectionAxis(double localOffset) {
        if (Math.abs(localOffset) < 0.15) {
            return 0;
        }
        if (Math.abs(localOffset - 1) < 0.15) {
            return 1;
        }
        if (Math.abs(localOffset + 1) < 0.15) {
            return -1;
        }
        return Integer.MIN_VALUE;
    }

    private static int connectionBit(int dx, int dy) {
        if (dx < 0 && dy < 0) {
            return CONNECTION_TOP_LEFT;
        }
        if (dx > 0 && dy < 0) {
            return CONNECTION_TOP_RIGHT;
        }
        if (dx < 0 && dy > 0) {
            return CONNECTION_BOTTOM_LEFT;
        }
        if (dx > 0 && dy > 0) {
            return CONNECTION_BOTTOM_RIGHT;
        }
        if (dx < 0) {
            return CONNECTION_LEFT;
        }
        if (dx > 0) {
            return CONNECTION_RIGHT;
        }
        return dy < 0 ? CONNECTION_UP : CONNECTION_DOWN;
    }

    private Vec3 nodeWorldCenter(GreenPrintNode node, double centerX, double centerY) {
        double localX = node.x() + 0.5 - centerX;
        double localY = node.y() + 0.5 - centerY;
        Vec3 xAxis = renderXAxis();
        Vec3 yAxis = renderYAxis();
        return entityCenter().add(xAxis.scale(localX)).add(yAxis.scale(localY));
    }

    private Vec3 entityCenter() {
        AABB box = getBoundingBox();
        return new Vec3(axisCenter(box, Direction.Axis.X), axisCenter(box, Direction.Axis.Y),
                axisCenter(box, Direction.Axis.Z));
    }

    private Vec3 renderXAxis() {
        double angle = Math.toRadians(-getYRot());
        return new Vec3(Math.cos(angle), 0, -Math.sin(angle));
    }

    private Vec3 renderYAxis() {
        double angle = Math.toRadians(-getYRot());
        double pitch = Math.toRadians(90 + getXRot());
        double cosPitch = Math.cos(pitch);
        return new Vec3(Math.sin(angle) * cosPitch, -Math.sin(pitch), Math.cos(angle) * cosPitch);
    }

    private static boolean edgeTouches(AABB first, AABB second, Direction.Axis axis) {
        double firstMin = axisMin(first, axis);
        double firstMax = axisMax(first, axis);
        double secondMin = axisMin(second, axis);
        double secondMax = axisMax(second, axis);
        return Math.abs(firstMax - secondMin) < 0.1 || Math.abs(secondMax - firstMin) < 0.1;
    }

    private static boolean overlaps(AABB first, AABB second, Direction.Axis axis) {
        return Math.min(axisMax(first, axis), axisMax(second, axis))
                - Math.max(axisMin(first, axis), axisMin(second, axis)) > -0.1;
    }

    private static double axisMin(AABB box, Direction.Axis axis) {
        return switch (axis) {
            case X -> box.minX;
            case Y -> box.minY;
            case Z -> box.minZ;
        };
    }

    private static double axisMax(AABB box, Direction.Axis axis) {
        return switch (axis) {
            case X -> box.maxX;
            case Y -> box.maxY;
            case Z -> box.maxZ;
        };
    }

    private static double axisCenter(AABB box, Direction.Axis axis) {
        return (axisMin(box, axis) + axisMax(box, axis)) / 2;
    }

    private int sectionIndexAt(Vec3 hit, GreenPrintGraph graph) {
        int width = Math.max(1, graphWidth(graph));
        int height = Math.max(1, graphHeight(graph));
        if (width == 1 && height == 1) {
            return 0;
        }

        int minX = minGraphX();
        int minY = minGraphY();
        double centerX = (minX + maxGraphX() + 1) / 2.0;
        double centerY = (minY + maxGraphY() + 1) / 2.0;

        Vec3 local = VecHelper.rotate(hit, getYRot(), Direction.Axis.Y);
        local = VecHelper.rotate(local, -getXRot(), Direction.Axis.X);
        int x = Mth.floor(local.x + centerX) - minX;
        int y = Mth.floor(local.y + centerY) - minY;
        x = Mth.clamp(x, 0, width - 1);
        y = Mth.clamp(y, 0, height - 1);
        return y * size + x;
    }

    private static boolean sameNodeLayout(GreenPrintGraph first, GreenPrintGraph second) {
        if (first.nodes().size() != second.nodes().size()) {
            return false;
        }
        for (GreenPrintNode node : first.nodes()) {
            GreenPrintNode other = second.nodeAt(node.x(), node.y());
            if (other == null || !node.id().equals(other.id())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void dropItem(Entity breaker) {
        if (!level().isClientSide) {
            ItemStack stack = CreateGreenPrint.GREEN_PRINT.get().getDefaultInstance();
            spawnAtLocation(stack, 0.1f);
        }
        discard();
    }
}
