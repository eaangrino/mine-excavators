package eaangrino;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import eaangrino.config.MineExcavatorsConfig;
import eaangrino.item.ExcavatorItem;
import eaangrino.item.material.ExcavatorMaterial;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class MineExcavators implements ModInitializer {
	public static final String MOD_ID = "mine-excavators";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Gson GSON = new Gson();
	private static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES_TAB = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			ResourceLocation.withDefaultNamespace("tools_and_utilities")
	);

	public static final Map<String, Item> EXCAVATORS = new LinkedHashMap<>();

	@Override
	public void onInitialize() {
		MineExcavatorsConfig.load();
		registerBlockAttackTracking();
		registerExcavatorsFromStaticData();
		registerCreativeTabEntries();
		LOGGER.info("Registered {} excavators for {}", EXCAVATORS.size(), MOD_ID);
	}

	private static void registerBlockAttackTracking() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (!world.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer && player.getItemInHand(hand).getItem() instanceof ExcavatorItem) {
				ExcavatorItem.rememberLastMinedFace(serverPlayer, direction);
			}
			return InteractionResult.PASS;
		});
	}

	private static void registerExcavatorsFromStaticData() {
		Optional<Path> staticDataDir = FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.flatMap(modContainer -> modContainer.findPath("static_data/" + MOD_ID + "/excavators"));

		if (staticDataDir.isEmpty()) {
			LOGGER.error("Could not find static excavator data directory");
			return;
		}

		try (Stream<Path> files = Files.list(staticDataDir.get())) {
			files
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.sorted()
				.forEach(MineExcavators::registerExcavatorFromFile);
		} catch (IOException e) {
			LOGGER.error("Failed to list excavator definitions", e);
		}
	}

	private static void registerExcavatorFromFile(Path file) {
		try (BufferedReader reader = Files.newBufferedReader(file)) {
			ExcavatorDefinition definition = GSON.fromJson(reader, ExcavatorDefinition.class);
			if (definition == null || definition.id() == null || definition.id().isBlank()) {
				throw new JsonParseException("Missing excavator id");
			}

			String excavatorName = definition.id() + "_excavator";
			ResourceLocation itemId = ResourceLocation.fromNamespaceAndPath(MOD_ID, excavatorName);
			Item item = createExcavatorItem(definition);
			Registry.register(BuiltInRegistries.ITEM, itemId, item);
			EXCAVATORS.put(excavatorName, item);

			if (definition.burnTime() > 0) {
				FuelRegistry.INSTANCE.add(item, definition.burnTime());
			}
		} catch (IOException | JsonParseException | IllegalStateException e) {
			LOGGER.error("Failed to register excavator from {}", file, e);
		}
	}

	private static Item createExcavatorItem(ExcavatorDefinition definition) {
		TagKey<Block> inverseTag = getIncorrectBlocksTag(definition.miningLevel());
		Ingredient repairIngredient = ingredientFromItemId(definition.repairIngredient());

		ExcavatorMaterial material = new ExcavatorMaterial(
				inverseTag,
				definition.durability(),
				(float) definition.blockBreakSpeed(),
				(float) definition.attackDamage(),
				definition.enchantability(),
				repairIngredient
		);

		Item.Properties settings = new Item.Properties().durability(definition.durability());
		if (definition.isFireImmune()) {
			settings = settings.fireResistant();
		}

		float extraKnockback = 0.0F;
		if (definition.isExtra()) {
			extraKnockback += 0.5F;
		}
		if (definition.hasExtraKnockback()) {
			extraKnockback += 1.0F;
		}

		return new ExcavatorItem(
				material,
				(float) definition.attackDamage(),
				(float) definition.attackSpeed(),
				extraKnockback,
				definition.smelts(),
				settings
		);
	}

	private static Ingredient ingredientFromItemId(String itemId) {
		ResourceLocation identifier = ResourceLocation.tryParse(itemId);
		if (identifier == null) {
			throw new IllegalStateException("Invalid repair ingredient id: " + itemId);
		}

		Item repairItem = BuiltInRegistries.ITEM.get(identifier);
		if (repairItem == null || repairItem == Items.AIR) {
			throw new IllegalStateException("Unknown repair ingredient item: " + itemId);
		}

		return Ingredient.of(repairItem);
	}

	private static TagKey<Block> getIncorrectBlocksTag(int miningLevel) {
		return switch (miningLevel) {
			case 0 -> BlockTags.INCORRECT_FOR_WOODEN_TOOL;
			case 1 -> BlockTags.INCORRECT_FOR_STONE_TOOL;
			case 2 -> BlockTags.INCORRECT_FOR_IRON_TOOL;
			case 3 -> BlockTags.INCORRECT_FOR_DIAMOND_TOOL;
			default -> BlockTags.INCORRECT_FOR_NETHERITE_TOOL;
		};
	}

	private static void registerCreativeTabEntries() {
		ItemGroupEvents.modifyEntriesEvent(TOOLS_AND_UTILITIES_TAB)
				.register(entries -> EXCAVATORS.values().forEach(entries::accept));
	}

	private record ExcavatorDefinition(
			String id,
			int miningLevel,
			int durability,
			double blockBreakSpeed,
			double attackDamage,
			double attackSpeed,
			int enchantability,
			String repairIngredient,
			boolean isExtra,
			int burnTime,
			boolean isFireImmune,
			boolean smelts,
			boolean hasExtraKnockback
	) {
	}
}
