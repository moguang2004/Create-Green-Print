package com.moguang.creategreenprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.foundation.gui.AllGuiTextures;

import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Renders the Create-style material overlay for Green Print entities. */
@Mod.EventBusSubscriber(modid = CreateGreenPrint.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class GreenPrintRequirementOverlay {
    private static final ResourceLocation CREATE_BLUEPRINT_OVERLAY =
            ResourceLocation.fromNamespaceAndPath("create", "blueprint");
    private static final Map<Ingredient, Map<Integer, Boolean>> RECURSIVE_REQUIREMENTS = new IdentityHashMap<>();
    private static final Map<Ingredient, Boolean> PRODUCER_RECIPES = new IdentityHashMap<>();

    public static final IGuiOverlay OVERLAY = GreenPrintRequirementOverlay::renderOverlay;

    private static UUID cachedEntity;
    private static long cachedAt = Long.MIN_VALUE;

    private GreenPrintRequirementOverlay() {
    }

    /** Create still sees GreenPrintEntity as a BlueprintEntity, so suppress its own duplicate HUD. */
    @SubscribeEvent
    public static void cancelCreateBlueprintOverlay(RenderGuiOverlayEvent.Pre event) {
        if (!CREATE_BLUEPRINT_OVERLAY.equals(event.getOverlay().id()) || !isLookingAtGreenPrint()) {
            return;
        }
        event.setCanceled(true);
    }

    /** Uses Create's original slots, arrow, item renderer and number decorations. */
    public static void renderOverlay(ForgeGui gui, GuiGraphics graphics, float partialTick,
                                     int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null || minecraft.player == null
                || minecraft.level == null) {
            return;
        }

        Target target = target(minecraft);
        if (target == null || !target.entity().hasRecipeAt(target.localHit())) {
            return;
        }

        GreenPrintNode node = target.entity().recipeNodeAt(target.localHit());
        if (node == null) {
            return;
        }

        refreshDecisionCache(target.entity(), minecraft.level.getGameTime());
        List<Requirement> requirements = requirementsFor(node, minecraft.player);
        if (requirements.isEmpty()) {
            return;
        }

        boolean resultCraftable = requirements.stream().allMatch(Requirement::directlyAvailable);
        int width = 21 * requirements.size() + 21 + 30;
        int x = (screenWidth - width) / 2;
        int y = screenHeight - 100;

        for (Requirement requirement : requirements) {
            RenderSystem.enableBlend();
            (requirement.directlyAvailable() ? AllGuiTextures.HOTSLOT_ACTIVE : AllGuiTextures.HOTSLOT)
                    .render(graphics, x, y);
            drawItemStack(graphics, minecraft, x, y, requirement.display().copyWithCount(requirement.count()),
                    requirement.directlyAvailable() ? null
                            : missingCountText(target.entity(), minecraft.player, requirement));
            x += 21;
        }

        x += 5;
        RenderSystem.enableBlend();
        AllGuiTextures.HOTSLOT_ARROW.render(graphics, x, y + 4);
        x += 25;

        ItemStack output = node.output();
        if (output.isEmpty()) {
            AllGuiTextures.HOTSLOT.render(graphics, x, y);
            GuiGameElement.of(Items.BARRIER).at(x + 3, y + 3).render(graphics);
        } else {
            AllGuiTextures resultSlot = resultCraftable ? AllGuiTextures.HOTSLOT_SUPER_ACTIVE : AllGuiTextures.HOTSLOT;
            resultSlot.render(graphics, resultCraftable ? x - 1 : x, resultCraftable ? y - 1 : y);
            drawItemStack(graphics, minecraft, x, y, output, null);
        }
        RenderSystem.disableBlend();
    }

    private static boolean isLookingAtGreenPrint() {
        return target(Minecraft.getInstance()) != null;
    }

    private static Target target(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof GreenPrintEntity greenPrint)) {
            return null;
        }
        return new Target(greenPrint, hit.getLocation().subtract(greenPrint.position()));
    }

    private static void refreshDecisionCache(GreenPrintEntity greenPrint, long gameTime) {
        if (cachedEntity == null || !greenPrint.getUUID().equals(cachedEntity) || gameTime - cachedAt >= 10) {
            RECURSIVE_REQUIREMENTS.clear();
            PRODUCER_RECIPES.clear();
            cachedEntity = greenPrint.getUUID();
            cachedAt = gameTime;
        }
    }

    private static String missingCountText(GreenPrintEntity greenPrint, Player player, Requirement requirement) {
        ChatFormatting color = missingCountColor(greenPrint, player, requirement);
        return color + Integer.toString(requirement.count());
    }

    private static ChatFormatting missingCountColor(GreenPrintEntity greenPrint, Player player,
                                                    Requirement requirement) {
        boolean hasRecipe = PRODUCER_RECIPES.computeIfAbsent(requirement.ingredient(), greenPrint::hasCraftingRecipe);
        if (!hasRecipe) {
            return ChatFormatting.RED;
        }

        Map<Integer, Boolean> byCount = RECURSIVE_REQUIREMENTS.computeIfAbsent(requirement.ingredient(),
                ignored -> new HashMap<>());
        boolean recursivelyAvailable = byCount.computeIfAbsent(requirement.count(), count ->
                greenPrint.canRecursivelyCraft(player, requirement.ingredient(), count));
        return recursivelyAvailable ? ChatFormatting.AQUA : ChatFormatting.GOLD;
    }

    /** Allocates inventory copies so tag alternatives and repeated ingredients are counted correctly. */
    private static List<Requirement> requirementsFor(GreenPrintNode node, Player player) {
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
            requirements.add(new Requirement(group.ingredient(), displayStack(group.ingredient(), player),
                    group.count(), directlyAvailable[index]));
        }
        return requirements;
    }

    private static ItemStack displayStack(Ingredient ingredient, Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                return stack.copyWithCount(1);
            }
        }
        ItemStack[] choices = ingredient.getItems();
        return choices.length == 0 ? ItemStack.EMPTY : choices[0].copyWithCount(1);
    }

    private static void drawItemStack(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                      ItemStack itemStack, String count) {
        GuiGameElement.of(itemStack).at(x + 3, y + 3).render(graphics);
        graphics.renderItemDecorations(minecraft.font, itemStack, x + 3, y + 3, count);
    }

    private record Target(GreenPrintEntity entity, Vec3 localHit) {
    }

    private record Requirement(Ingredient ingredient, ItemStack display, int count, boolean directlyAvailable) {
    }
}
