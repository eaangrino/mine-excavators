package eaangrino.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import eaangrino.config.MineExcavatorsConfig;
import eaangrino.item.ExcavatorItem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ExcavatorAreaOutlineRenderer {
	private static final float OUTLINE_RED = 0.0F;
	private static final float OUTLINE_GREEN = 0.0F;
	private static final float OUTLINE_BLUE = 0.0F;
	private static final float OUTLINE_ALPHA = 0.4F;

	private ExcavatorAreaOutlineRenderer() {
	}

	public static void register() {
		WorldRenderEvents.BLOCK_OUTLINE.register((context, blockOutlineContext) -> {
			Minecraft minecraft = Minecraft.getInstance();
			LocalPlayer player = minecraft.player;
			if (minecraft.level == null || player == null) {
				return true;
			}

			ItemStack heldItem = player.getMainHandItem();
			if (!(heldItem.getItem() instanceof ExcavatorItem)) {
				return true;
			}

			MineExcavatorsConfig.ConfigData config = MineExcavatorsConfig.get();
			if (!config.areaMiningEnabled || config.radius <= 0) {
				return true;
			}

			if (config.disableWhenSneaking && player.isShiftKeyDown()) {
				return true;
			}

			HitResult hitResult = minecraft.hitResult;
			if (!(hitResult instanceof BlockHitResult blockHitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
				return true;
			}

			PoseStack poseStack = context.matrixStack();
			if (poseStack == null || context.consumers() == null) {
				return true;
			}

			Direction.Axis axis = getMiningPlaneAxis(player.getXRot(), player.getDirection());
			BlockPos origin = blockOutlineContext.blockPos();
			VertexConsumer vertexConsumer = context.consumers().getBuffer(RenderType.lines());
			double cameraX = blockOutlineContext.cameraX();
			double cameraY = blockOutlineContext.cameraY();
			double cameraZ = blockOutlineContext.cameraZ();

			for (int first = -config.radius; first <= config.radius; first++) {
				for (int second = -config.radius; second <= config.radius; second++) {
					if (first == 0 && second == 0) {
						continue;
					}

					BlockPos targetPos = switch (axis) {
						case X -> origin.offset(0, first, second);
						case Y -> origin.offset(first, 0, second);
						case Z -> origin.offset(first, second, 0);
					};

					if (!player.canInteractWithBlock(targetPos, 1.0D) || !player.mayUseItemAt(targetPos, blockHitResult.getDirection(), heldItem)) {
						continue;
					}

					BlockState targetState = minecraft.level.getBlockState(targetPos);
					if (targetState.isAir() || targetState.getDestroySpeed(minecraft.level, targetPos) < 0.0F) {
						continue;
					}

					if (config.onlyShovelMineable && !targetState.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) {
						continue;
					}

					if (config.requireCorrectToolForDrops && !player.hasCorrectToolForDrops(targetState)) {
						continue;
					}

					VoxelShape shape = targetState.getShape(minecraft.level, targetPos, CollisionContext.of(player));
					if (shape.isEmpty()) {
						continue;
					}

					LevelRenderer.renderVoxelShape(
							poseStack,
							vertexConsumer,
							shape,
							targetPos.getX() - cameraX,
							targetPos.getY() - cameraY,
							targetPos.getZ() - cameraZ,
							OUTLINE_RED,
							OUTLINE_GREEN,
							OUTLINE_BLUE,
							OUTLINE_ALPHA,
							false
					);
				}
			}

			return true;
		});
	}

	private static Direction.Axis getMiningPlaneAxis(float xRot, Direction direction) {
		if (Math.abs(xRot) > 45.0F) {
			return Direction.Axis.Y;
		}

		return direction.getAxis();
	}
}
