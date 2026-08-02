package com.moguang.creategreenprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * The persistent part of a Green Print. Coordinates are intentionally not
 * bounded: adjacency, rather than a recipe grid size, defines the graph.
 */
public final class GreenPrintGraph {
    private static final int DATA_VERSION = 4;
    private static final String NODES = "Nodes";
    private final List<GreenPrintNode> nodes;
    private final Map<Long, GreenPrintNode> nodesByPosition;

    public GreenPrintGraph() {
        this(List.of());
    }

    public GreenPrintGraph(List<GreenPrintNode> nodes) {
        this.nodes = List.copyOf(nodes);
        Map<Long, GreenPrintNode> indexed = new HashMap<>();
        for (GreenPrintNode node : this.nodes) {
            indexed.put(positionKey(node.x(), node.y()), node);
        }
        this.nodesByPosition = Map.copyOf(indexed);
    }

    public List<GreenPrintNode> nodes() {
        return nodes;
    }

    public GreenPrintGraph withNode(GreenPrintNode node) {
        List<GreenPrintNode> updated = new ArrayList<>(nodes);
        updated.removeIf(existing -> existing.id().equals(node.id()));
        updated.add(node);
        return new GreenPrintGraph(updated);
    }

    public GreenPrintGraph withoutNode(ResourceLocation id) {
        return new GreenPrintGraph(nodes.stream().filter(node -> !node.id().equals(id)).toList());
    }

    public GreenPrintNode node(ResourceLocation id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst().orElse(null);
    }

    public GreenPrintNode nodeAt(int x, int y) {
        return nodesByPosition.get(positionKey(x, y));
    }

    public GreenPrintGraph withoutNodeAt(int x, int y) {
        return new GreenPrintGraph(nodes.stream()
                .filter(node -> node.x() != x || node.y() != y)
                .toList());
    }

    public boolean isAdjacentToGraph(int x, int y) {
        if (nodes.isEmpty()) {
            return x == 0 && y == 0;
        }
        return nodeAt(x + 1, y) != null || nodeAt(x - 1, y) != null
                || nodeAt(x, y + 1) != null || nodeAt(x, y - 1) != null;
    }

    public List<GreenPrintNode> connectedTo(GreenPrintNode start) {
        Map<Long, GreenPrintNode> byPosition = nodesByPosition;
        Set<Long> visited = new HashSet<>();
        ArrayDeque<GreenPrintNode> pending = new ArrayDeque<>();
        pending.add(start);
        List<GreenPrintNode> result = new ArrayList<>();
        while (!pending.isEmpty()) {
            GreenPrintNode current = pending.removeFirst();
            long key = positionKey(current.x(), current.y());
            if (!visited.add(key)) {
                continue;
            }
            result.add(current);
            for (int[] direction : DIRECTIONS) {
                GreenPrintNode adjacent = byPosition.get(positionKey(current.x() + direction[0], current.y() + direction[1]));
                if (adjacent != null) {
                    pending.addLast(adjacent);
                }
            }
        }
        return result;
    }

    /** Picks the first empty coordinate adjacent to the existing graph. */
    public int[] nextAdjacentPosition() {
        if (nodes.isEmpty()) {
            return new int[] {0, 0};
        }
        Map<Long, GreenPrintNode> byPosition = nodesByPosition;
        for (GreenPrintNode node : nodes) {
            for (int[] direction : DIRECTIONS) {
                int x = node.x() + direction[0];
                int y = node.y() + direction[1];
                if (!byPosition.containsKey(positionKey(x, y))) {
                    return new int[] {x, y};
                }
            }
        }
        throw new IllegalStateException("No adjacent Green Print position available");
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", DATA_VERSION);
        ListTag serializedNodes = new ListTag();
        for (GreenPrintNode node : nodes) {
            CompoundTag serialized = new CompoundTag();
            serialized.putString("Id", node.id().toString());
            serialized.putInt("X", node.x());
            serialized.putInt("Y", node.y());
            serialized.put("Output", node.output().save(new CompoundTag()));
            ListTag ingredients = new ListTag();
            for (GreenPrintIngredientGroup group : node.ingredientGroups()) {
                CompoundTag entry = new CompoundTag();
                Tag value = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, group.ingredient().toJson());
                entry.put("Data", value);
                entry.putIntArray("Slots", group.slots().stream().mapToInt(Integer::intValue).toArray());
                ingredients.add(entry);
            }
            serialized.put("Ingredients", ingredients);
            serializedNodes.add(serialized);
        }
        root.put(NODES, serializedNodes);
        return root;
    }

    public static GreenPrintGraph load(HolderLookup.Provider registries, CompoundTag root) {
        if (!root.contains(NODES, CompoundTag.TAG_LIST)) {
            return new GreenPrintGraph();
        }
        List<GreenPrintNode> loaded = new ArrayList<>();
        ListTag serializedNodes = root.getList(NODES, CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < serializedNodes.size(); i++) {
            CompoundTag serialized = serializedNodes.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(serialized.getString("Id"));
            if (id == null || !serialized.contains("Output", CompoundTag.TAG_COMPOUND)) {
                continue;
            }
            ItemStack output = ItemStack.of(serialized.getCompound("Output"));
            if (output.isEmpty()) {
                continue;
            }
            List<Ingredient> legacyGrid = new ArrayList<>();
            List<GreenPrintIngredientGroup> ingredients = new ArrayList<>();
            ListTag serializedIngredients = serialized.getList("Ingredients", CompoundTag.TAG_END);
            for (int j = 0; j < serializedIngredients.size(); j++) {
                Tag encodedIngredient = serializedIngredients.get(j);
                int[] slots = new int[0];
                // Version 1 stored the codec value directly. Retain that read path
                // so existing Green Prints are rewritten safely on their next sync.
                if (encodedIngredient instanceof CompoundTag entry && entry.contains("Data")) {
                    if (entry.contains("Slots")) {
                        slots = entry.getIntArray("Slots");
                    }
                    encodedIngredient = entry.get("Data");
                }
                int[] encodedSlots = slots;
                try {
                    Ingredient ingredient = Ingredient.fromJson(
                            NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, encodedIngredient));
                    if (encodedSlots.length == 0) {
                        legacyGrid.add(ingredient);
                    } else {
                        List<Integer> groupSlots = new ArrayList<>(encodedSlots.length);
                        for (int slot : encodedSlots) {
                            if (slot >= 0 && slot < 9) {
                                groupSlots.add(slot);
                            }
                        }
                        if (!groupSlots.isEmpty()) {
                            ingredients.add(new GreenPrintIngredientGroup(ingredient, groupSlots));
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Invalid ingredients are skipped so a damaged print does not prevent world loading.
                }
            }
            if (!legacyGrid.isEmpty()) {
                ingredients.addAll(GreenPrintIngredientGroup.compact(legacyGrid));
            }
            loaded.add(new GreenPrintNode(id, serialized.getInt("X"), serialized.getInt("Y"), ingredients, output));
        }
        return new GreenPrintGraph(loaded);
    }

    private static long positionKey(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
}
