package com.moguang.creategreenprint;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.simibubi.create.content.equipment.blueprint.BlueprintItem;

/** A stackable blank Green Print. Recipes belong to placed Green Print entities. */
public class GreenPrintItem extends BlueprintItem {
    public GreenPrintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        Direction face = context.getClickedFace();
        BlockPos position = context.getClickedPos().relative(face);
        if (player == null || !player.mayUseItemAt(position, face, context.getItemInHand())) {
            return InteractionResult.FAIL;
        }
        GreenPrintEntity entity = new GreenPrintEntity(level, position, face,
                face.getAxis().isHorizontal() ? Direction.DOWN : context.getHorizontalDirection());
        if (!entity.survives()) {
            return InteractionResult.CONSUME;
        }
        if (!level.isClientSide) {
            entity.playPlacementSound();
            level.addFreshEntity(entity);
        }
        context.getItemInHand().shrink(1);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
        // Convert pre-change Green Prints into normal stackable blank items.
        if (!level.isClientSide && stack.hasTag()) {
            stack.setTag(null);
        }
    }

}
