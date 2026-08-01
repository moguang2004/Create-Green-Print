package com.moguang.creategreenprint;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders one connected, arbitrarily shaped tile for every Green Print node. */
public final class GreenPrintRenderer extends EntityRenderer<GreenPrintEntity> {
    public GreenPrintRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(GreenPrintEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        List<GreenPrintEntity.RenderTile> tiles = entity.renderTiles();
        int width = entity.renderWidth();
        int height = entity.renderHeight();
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        Map<Long, Integer> externalMasks = entity.externalConnectionMasksForRender(tiles, width, height);
        ItemRenderState itemRenderState = itemRenderState(entity, entityYaw, packedLight, poseStack);

        if (tiles.isEmpty()) {
            renderTile(entity, entityYaw, 0, 0,
                    connectionTextureIndex(externalMasks.getOrDefault(GreenPrintEntity.renderPositionKey(0, 0), 0)),
                    centerX, centerY,
                    poseStack, bufferSource, packedLight);
            return;
        }

        Set<Long> occupied = new HashSet<>();
        for (GreenPrintEntity.RenderTile tile : tiles) {
            occupied.add(positionKey(tile.x(), tile.y()));
        }
        for (GreenPrintEntity.RenderTile tile : tiles) {
            int connectionMask = connectionMask(occupied, tile.x(), tile.y())
                    | externalMasks.getOrDefault(GreenPrintEntity.renderPositionKey(tile.x(), tile.y()), 0);
            renderTile(entity, entityYaw, tile.x(), tile.y(), connectionTextureIndex(connectionMask), centerX, centerY,
                    poseStack, bufferSource, packedLight);
            renderOutput(entity, entityYaw, tile.output(), tile.x(), tile.y(), centerX, centerY,
                    poseStack, bufferSource, itemRenderState);
        }
    }

    private static long positionKey(int x, int y) {
        return GreenPrintEntity.renderPositionKey(x, y);
    }

    private static void renderTile(GreenPrintEntity entity, float entityYaw, int x, int y, int mask,
                                   double centerX, double centerY, PoseStack poseStack,
                                   MultiBufferSource bufferSource, int packedLight) {
        renderTilePart(entity, entityYaw, x, y, centerX, centerY,
                CachedBuffers.partial(GreenPrintPartialModels.BASE, Blocks.AIR.defaultBlockState()),
                poseStack, bufferSource, packedLight);

        int tileX = mask & 7;
        int tileY = mask >> 3;
        SuperByteBuffer connection = CachedBuffers.partial(GreenPrintPartialModels.CONNECTED,
                Blocks.AIR.defaultBlockState());
        // The target is an 8x8 sheet of 16x16 variants. The model keeps the
        // original 16x16 UVs and shiftUVtoSheet selects one target variant.
        connection.shiftUVtoSheet(GreenPrintPartialModels.CONNECTION_SHEET,
                tileX / 8f, tileY / 8f, 8);
        renderTilePart(entity, entityYaw, x, y, centerX, centerY, connection,
                poseStack, bufferSource, packedLight);
    }

    private static void renderTilePart(GreenPrintEntity entity, float entityYaw, int x, int y,
                                       double centerX, double centerY, SuperByteBuffer tile,
                                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        tile.rotateYDegrees(-entityYaw)
                .rotateXDegrees(90 + entity.getXRot())
                .translate(x - centerX, -0.03125, y - centerY)
                .disableDiffuse()
                .light(packedLight)
                .renderInto(poseStack, bufferSource.getBuffer(Sheets.solidBlockSheet()));
    }

    /** Mirrors Create's BlueprintRenderer output-item pass for each configured section. */
    private static void renderOutput(GreenPrintEntity entity, float entityYaw, ItemStack output, int x, int y,
                                     double centerX, double centerY, PoseStack poseStack,
                                     MultiBufferSource bufferSource, ItemRenderState itemRenderState) {
        if (output.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-entityYaw));
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(entity.getXRot()));
        poseStack.translate(x - centerX + 0.5, y - centerY + 0.5, 0.03225);
        poseStack.scale(0.5f, 0.5f, 0.0009765625f);
        poseStack.last().normal().set(itemRenderState.normal());
        Minecraft.getInstance().getItemRenderer().renderStatic(
                output,
                ItemDisplayContext.GUI,
                itemRenderState.light(),
                OverlayTexture.NO_OVERLAY,
                poseStack,
                bufferSource,
                entity.level(),
                0);
        poseStack.popPose();
    }

    /** Uses Create's fixed GUI-item normal so the item icons do not darken as the print rotates. */
    private static ItemRenderState itemRenderState(GreenPrintEntity entity, float entityYaw, int packedLight,
                                                   PoseStack poseStack) {
        float normalXRotation = -15;
        int blockLight = packedLight >> 4 & 0xf;
        int skyLight = packedLight >> 20 & 0xf;
        boolean vertical = entity.getXRot() != 0;
        if (entity.getXRot() == -90) {
            normalXRotation = -45;
        } else if (entity.getXRot() == 90 || entityYaw % 180 != 0) {
            blockLight /= 1.35f;
            skyLight /= 1.35f;
        }

        poseStack.pushPose();
        if (!vertical) {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-entityYaw));
        }
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(normalXRotation));
        Matrix3f normal = new Matrix3f(poseStack.last().normal());
        poseStack.popPose();

        int itemLight = Mth.floor(skyLight + .5f) << 20 | (Mth.floor(blockLight + .5f) & 0xf) << 4;
        return new ItemRenderState(normal, itemLight);
    }

    private record ItemRenderState(Matrix3f normal, int light) {
    }

    private static int connectionMask(Set<Long> occupied, int x, int y) {
        int mask = 0;
        if (occupied.contains(positionKey(x - 1, y))) {
            mask |= GreenPrintEntity.CONNECTION_LEFT;
        }
        if (occupied.contains(positionKey(x + 1, y))) {
            mask |= GreenPrintEntity.CONNECTION_RIGHT;
        }
        if (occupied.contains(positionKey(x, y - 1))) {
            mask |= GreenPrintEntity.CONNECTION_UP;
        }
        if (occupied.contains(positionKey(x, y + 1))) {
            mask |= GreenPrintEntity.CONNECTION_DOWN;
        }
        if (occupied.contains(positionKey(x - 1, y - 1))) {
            mask |= GreenPrintEntity.CONNECTION_TOP_LEFT;
        }
        if (occupied.contains(positionKey(x + 1, y - 1))) {
            mask |= GreenPrintEntity.CONNECTION_TOP_RIGHT;
        }
        if (occupied.contains(positionKey(x - 1, y + 1))) {
            mask |= GreenPrintEntity.CONNECTION_BOTTOM_LEFT;
        }
        if (occupied.contains(positionKey(x + 1, y + 1))) {
            mask |= GreenPrintEntity.CONNECTION_BOTTOM_RIGHT;
        }
        return mask;
    }

    /** Mirrors Create's AllCTTypes.OMNIDIRECTIONAL atlas lookup. */
    private static int connectionTextureIndex(int mask) {
        boolean up = (mask & GreenPrintEntity.CONNECTION_UP) != 0;
        boolean down = (mask & GreenPrintEntity.CONNECTION_DOWN) != 0;
        boolean left = (mask & GreenPrintEntity.CONNECTION_LEFT) != 0;
        boolean right = (mask & GreenPrintEntity.CONNECTION_RIGHT) != 0;
        boolean topLeft = up && left && (mask & GreenPrintEntity.CONNECTION_TOP_LEFT) != 0;
        boolean topRight = up && right && (mask & GreenPrintEntity.CONNECTION_TOP_RIGHT) != 0;
        boolean bottomLeft = down && left && (mask & GreenPrintEntity.CONNECTION_BOTTOM_LEFT) != 0;
        boolean bottomRight = down && right && (mask & GreenPrintEntity.CONNECTION_BOTTOM_RIGHT) != 0;

        int tileX = 0;
        int tileY = 0;
        int borders = (!up ? 1 : 0) + (!down ? 1 : 0) + (!left ? 1 : 0) + (!right ? 1 : 0);

        if (up) {
            tileX++;
        }
        if (down) {
            tileX += 2;
        }
        if (left) {
            tileY++;
        }
        if (right) {
            tileY += 2;
        }

        if (borders == 0) {
            if (topRight) {
                tileX++;
            }
            if (topLeft) {
                tileX += 2;
            }
            if (bottomRight) {
                tileY += 2;
            }
            if (bottomLeft) {
                tileY++;
            }
        }

        if (borders == 1) {
            if (!right && (topLeft || bottomLeft)) {
                tileY = 4;
                tileX = -1 + (bottomLeft ? 1 : 0) + (topLeft ? 2 : 0);
            }
            if (!left && (topRight || bottomRight)) {
                tileY = 5;
                tileX = -1 + (bottomRight ? 1 : 0) + (topRight ? 2 : 0);
            }
            if (!down && (topLeft || topRight)) {
                tileY = 6;
                tileX = -1 + (topLeft ? 1 : 0) + (topRight ? 2 : 0);
            }
            if (!up && (bottomLeft || bottomRight)) {
                tileY = 7;
                tileX = -1 + (bottomLeft ? 1 : 0) + (bottomRight ? 2 : 0);
            }
        }

        if (borders == 2
                && ((up && left && topLeft) || (down && left && bottomLeft)
                || (up && right && topRight) || (down && right && bottomRight))) {
            tileX += 3;
        }

        return tileX + 8 * tileY;
    }

    @Override
    public ResourceLocation getTextureLocation(GreenPrintEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(CreateGreenPrint.MODID, "textures/entity/greenprint_small.png");
    }
}
