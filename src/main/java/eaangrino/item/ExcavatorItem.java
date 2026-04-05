package eaangrino.item;

import eaangrino.config.MineExcavatorsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ExcavatorItem extends DiggerItem {
	private static final ThreadLocal<Boolean> AREA_MINING_ACTIVE = ThreadLocal.withInitial(() -> false);
	private static final Map<UUID, Direction> LAST_MINED_FACES = new ConcurrentHashMap<>();
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

	public static float getModifiedDestroySpeed(ItemStack stack, Level level, LivingEntity entity, BlockState state, float currentSpeed) {
		return applyExcavatorHasteBoost(entity, currentSpeed);
	}

	@Override
	public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity livingEntity) {
		boolean mined = super.mineBlock(stack, level, state, pos, livingEntity);

		if (level.isClientSide() || !(livingEntity instanceof ServerPlayer player) || AREA_MINING_ACTIVE.get()) {
			return mined;
		}

		boolean shouldSmelt = smeltsBlocks();
		Map<Item, Integer> inventoryBefore = shouldSmelt ? snapshotInventoryCounts(player.getInventory()) : Map.of();

		MineExcavatorsConfig.ConfigData config = MineExcavatorsConfig.get();
		if (!config.areaMiningEnabled || config.radius <= 0) {
			if (shouldSmelt) {
				smeltNearbyDrops(level, pos, 1.5D);
				smeltNewlyCollectedInventoryItems(player, level, inventoryBefore);
			}
			return mined;
		}

		if (config.disableWhenSneaking && player.isShiftKeyDown()) {
			if (shouldSmelt) {
				smeltNearbyDrops(level, pos, 1.5D);
				smeltNewlyCollectedInventoryItems(player, level, inventoryBefore);
			}
			return mined;
		}

		Direction.Axis axis = getMiningPlaneAxis(player, pos);
		AREA_MINING_ACTIVE.set(true);
		try {
			breakArea(stack, player, level, pos, axis, config);
		} finally {
			AREA_MINING_ACTIVE.set(false);
		}

		if (shouldSmelt) {
			smeltDropsInMinedArea(level, pos, axis, config.radius);
			smeltNewlyCollectedInventoryItems(player, level, inventoryBefore);
		}

		return mined;
	}

	public static void rememberLastMinedFace(ServerPlayer player, Direction face) {
		LAST_MINED_FACES.put(player.getUUID(), face);
	}

	private static Direction.Axis getMiningPlaneAxis(ServerPlayer player, BlockPos origin) {
		Direction hitFace = LAST_MINED_FACES.remove(player.getUUID());
		if (hitFace != null) {
			return hitFace.getAxis();
		}

		// Fallback for edge cases where no attack callback ran before the block finished breaking.
		// Looking mostly up/down mines a horizontal 3x3, otherwise mines a vertical 3x3 in front of the player.
		if (Math.abs(player.getXRot()) > 45.0F) {
			return Direction.Axis.Y;
		}

		return player.getDirection().getAxis();
	}

	private static float applyExcavatorHasteBoost(LivingEntity entity, float currentSpeed) {
		if (currentSpeed <= 1.0F) {
			return currentSpeed;
		}

		MobEffectInstance haste = entity.getEffect(MobEffects.DIG_SPEED);
		if (haste == null) {
			return currentSpeed;
		}

		// Excavators are intentionally slower than vanilla shovels, so Haste can feel underwhelming.
		// Give excavators a modest extra scaling per Haste level to keep the effect noticeable in play.
		float excavatorHasteMultiplier = 1.0F + 0.35F * (haste.getAmplifier() + 1);
		return currentSpeed * excavatorHasteMultiplier;
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

		if (player.gameMode.destroyBlock(targetPos)) {
			if (!player.getAbilities().instabuild) {
				player.causeFoodExhaustion(config.hungerExhaustionPerExtraBlock);
			}
		}
	}

	private static void smeltDropsInMinedArea(Level level, BlockPos origin, Direction.Axis axis, int radius) {
		int minX = origin.getX();
		int maxX = origin.getX();
		int minY = origin.getY();
		int maxY = origin.getY();
		int minZ = origin.getZ();
		int maxZ = origin.getZ();

		switch (axis) {
			case X -> {
				minY -= radius;
				maxY += radius;
				minZ -= radius;
				maxZ += radius;
			}
			case Y -> {
				minX -= radius;
				maxX += radius;
				minZ -= radius;
				maxZ += radius;
			}
			case Z -> {
				minX -= radius;
				maxX += radius;
				minY -= radius;
				maxY += radius;
			}
		}

		AABB searchArea = new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D).inflate(1.5D);
		smeltDropsInBox(level, searchArea);
	}

	private static void smeltNearbyDrops(Level level, BlockPos blockPos, double inflation) {
		smeltDropsInBox(level, new AABB(blockPos).inflate(inflation));
	}

	private static void smeltDropsInBox(Level level, AABB searchArea) {
		smeltDropsInBox(level, searchArea, true);
	}

	private static void smeltDropsInBox(Level level, AABB searchArea, boolean scheduleFollowUpPass) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		for (ItemEntity itemEntity : serverLevel.getEntitiesOfClass(ItemEntity.class, searchArea, entity -> entity.isAlive() && !entity.getItem().isEmpty())) {
			ItemStack smelted = smeltStack(serverLevel, itemEntity.getItem());
			if (!smelted.isEmpty()) {
				itemEntity.setItem(smelted);
			}
		}

		// Some drops can spawn just after block break processing; a second pass next tick catches late entities.
		if (scheduleFollowUpPass) {
			serverLevel.getServer().execute(() -> smeltDropsInBox(serverLevel, searchArea, false));
		}
	}

	private static ItemStack smeltStack(ServerLevel level, ItemStack input) {
		SingleRecipeInput recipeInput = new SingleRecipeInput(input);
		Optional<RecipeHolder<SmeltingRecipe>> optionalRecipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, recipeInput, level);
		if (optionalRecipe.isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack result = optionalRecipe.get().value().getResultItem(level.registryAccess()).copy();
		if (result.isEmpty()) {
			return ItemStack.EMPTY;
		}

		result.setCount(result.getCount() * input.getCount());
		return result;
	}

	private static Map<Item, Integer> snapshotInventoryCounts(Inventory inventory) {
		Map<Item, Integer> counts = new HashMap<>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	private static void smeltNewlyCollectedInventoryItems(ServerPlayer player, Level level, Map<Item, Integer> beforeCounts) {
		if (!(level instanceof ServerLevel serverLevel) || beforeCounts.isEmpty()) {
			return;
		}

		Inventory inventory = player.getInventory();
		Map<Item, Integer> afterCounts = snapshotInventoryCounts(inventory);
		for (Map.Entry<Item, Integer> entry : afterCounts.entrySet()) {
			int gainedCount = entry.getValue() - beforeCounts.getOrDefault(entry.getKey(), 0);
			if (gainedCount <= 0) {
				continue;
			}

			int removedCount = removeItemsFromInventory(inventory, entry.getKey(), gainedCount);
			if (removedCount <= 0) {
				continue;
			}

			ItemStack smelted = smeltStack(serverLevel, new ItemStack(entry.getKey(), removedCount));
			if (smelted.isEmpty()) {
				addOrDrop(player, new ItemStack(entry.getKey(), removedCount));
				continue;
			}

			addOrDrop(player, smelted);
		}
	}

	private static int removeItemsFromInventory(Inventory inventory, Item item, int countToRemove) {
		int removed = 0;
		for (int slot = 0; slot < inventory.getContainerSize() && removed < countToRemove; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || stack.getItem() != item) {
				continue;
			}

			int amount = Math.min(countToRemove - removed, stack.getCount());
			stack.shrink(amount);
			removed += amount;

			if (stack.isEmpty()) {
				inventory.setItem(slot, ItemStack.EMPTY);
			}
		}
		return removed;
	}

	private static void addOrDrop(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}

		Inventory inventory = player.getInventory();
		if (!inventory.add(stack) && !stack.isEmpty()) {
			player.drop(stack, false);
			return;
		}

		if (!stack.isEmpty()) {
			player.drop(stack, false);
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
