package eaangrino.item;

import eaangrino.config.MineExcavatorsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class ExcavatorItem extends DiggerItem {
	private static final ThreadLocal<Boolean> AREA_MINING_ACTIVE = ThreadLocal.withInitial(() -> false);
	private final boolean smelts;

	public ExcavatorItem(Tier material, float attackDamage, float attackSpeed, float extraKnockback, boolean smelts, Item.Properties properties) {
		super(
				material,
				BlockTags.MINEABLE_WITH_SHOVEL,
				properties.attributes(createExcavatorAttributes(material, attackDamage, attackSpeed, extraKnockback))
		);
		this.smelts = smelts;
	}

	public boolean smeltsBlocks() {
		return smelts;
	}

	@Override
	public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity livingEntity) {
		boolean mined = super.mineBlock(stack, level, state, pos, livingEntity);

		if (level.isClientSide() || !(livingEntity instanceof ServerPlayer player) || AREA_MINING_ACTIVE.get()) {
			return mined;
		}

		MineExcavatorsConfig.ConfigData config = MineExcavatorsConfig.get();
		if (!config.areaMiningEnabled || config.radius <= 0) {
			return mined;
		}

		if (config.disableWhenSneaking && player.isShiftKeyDown()) {
			return mined;
		}

		Direction.Axis axis = getMiningPlaneAxis(player);
		AREA_MINING_ACTIVE.set(true);
		try {
			breakArea(stack, player, level, pos, axis, config);
		} finally {
			AREA_MINING_ACTIVE.set(false);
		}

		return mined;
	}

	private static Direction.Axis getMiningPlaneAxis(ServerPlayer player) {
		// Looking mostly up/down mines a horizontal 3x3, otherwise mines a vertical 3x3 in front of the player.
		if (Math.abs(player.getXRot()) > 45.0F) {
			return Direction.Axis.Y;
		}

		return player.getDirection().getAxis();
	}

	private static void breakArea(ItemStack stack, ServerPlayer player, Level level, BlockPos origin, Direction.Axis axis, MineExcavatorsConfig.ConfigData config) {
		int radius = config.radius;
		for (int first = -radius; first <= radius; first++) {
			for (int second = -radius; second <= radius; second++) {
				if (first == 0 && second == 0) {
					continue;
				}

				BlockPos targetPos = switch (axis) {
					case X -> origin.offset(0, first, second);
					case Y -> origin.offset(first, 0, second);
					case Z -> origin.offset(first, second, 0);
				};

				tryBreakExtraBlock(stack, player, level, targetPos, config);
			}
		}
	}

	private static void tryBreakExtraBlock(ItemStack stack, ServerPlayer player, Level level, BlockPos targetPos, MineExcavatorsConfig.ConfigData config) {
		if (!player.canInteractWithBlock(targetPos, 1.0D) || !player.mayUseItemAt(targetPos, Direction.UP, stack)) {
			return;
		}

		BlockState targetState = level.getBlockState(targetPos);
		if (targetState.isAir() || targetState.getDestroySpeed(level, targetPos) < 0.0F) {
			return;
		}

		if (config.onlyShovelMineable && !targetState.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
			return;
		}

		if (config.requireCorrectToolForDrops && !player.hasCorrectToolForDrops(targetState)) {
			return;
		}

		if (player.gameMode.destroyBlock(targetPos) && !player.getAbilities().instabuild) {
			player.causeFoodExhaustion(config.hungerExhaustionPerExtraBlock);
		}
	}

	private static ItemAttributeModifiers createExcavatorAttributes(Tier material, float attackDamage, float attackSpeed, float extraKnockback) {
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		builder.add(
				Attributes.ATTACK_DAMAGE,
				new AttributeModifier(
						ResourceLocation.withDefaultNamespace("base_attack_damage"),
						attackDamage + material.getAttackDamageBonus(),
						AttributeModifier.Operation.ADD_VALUE
				),
				EquipmentSlotGroup.MAINHAND
		);
		builder.add(
				Attributes.ATTACK_SPEED,
				new AttributeModifier(
						ResourceLocation.withDefaultNamespace("base_attack_speed"),
						attackSpeed,
						AttributeModifier.Operation.ADD_VALUE
				),
				EquipmentSlotGroup.MAINHAND
		);

		if (extraKnockback > 0.0F) {
			builder.add(
					Attributes.ATTACK_KNOCKBACK,
					new AttributeModifier(
							ResourceLocation.fromNamespaceAndPath("mine-excavators", "excavator_bonus_knockback"),
							extraKnockback,
							AttributeModifier.Operation.ADD_VALUE
					),
					EquipmentSlotGroup.MAINHAND
			);
		}

		return builder.build();
	}
}
