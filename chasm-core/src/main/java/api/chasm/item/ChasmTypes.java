package api.chasm.item;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品类型全局注册表（单例）。
 *
 * <p>内置类型直接以静态字段暴露：{@link #NONE}（普通物品，当前 {@code ChasmItem} 行为）、
 * {@link #SWORD}（真正 {@link ChasmSwordItem} 子类，获得横扫/剑类附魔池语义）。</p>
 *
 * <p>自定义类型通过 {@link #register(String, String, ChasmItemFactory)} 声明：
 * 提供原版类映射工厂，即可复用类型的默认属性与标签模板，供跨模组查询。</p>
 */
public final class ChasmTypes {

	/** 内置：普通物品（默认 {@code ChasmItem} 行为）。 */
	public static final ChasmItemFactory NONE_FACTORY =
		ctx -> new ChasmItem(ctx.behavior(), ctx.properties());

	/** Chasm 剑类型的默认 Tier（可被 {@code ItemBuilder.tier()} 覆盖）。 */
	private static final Tier DEFAULT_SWORD_TIER = new ChasmSwordTier();

	/** Chasm 工具的默认 Tier（镐/斧/锹/锄共用，可被 {@code ItemBuilder.tier()} 覆盖）。 */
	private static final Tier DEFAULT_PICKAXE_TIER = new ChasmToolTier();
	private static final Tier DEFAULT_AXE_TIER = new ChasmToolTier();
	private static final Tier DEFAULT_SHOVEL_TIER = new ChasmToolTier();
	private static final Tier DEFAULT_HOE_TIER = new ChasmToolTier();

	/** 内置：剑（真正 {@link ChasmSwordItem} 子类）。 */
	public static final ChasmItemFactory SWORD_FACTORY =
		ctx -> new ChasmSwordItem(ctx.tier() != null ? ctx.tier() : DEFAULT_SWORD_TIER,
			ctx.behavior(), ctx.properties());

	/** 内置：镐（真正 {@link ChasmPickaxeItem} 子类）。 */
	public static final ChasmItemFactory PICKAXE_FACTORY =
		ctx -> new ChasmPickaxeItem(ctx.tier() != null ? ctx.tier() : DEFAULT_PICKAXE_TIER,
			ctx.behavior(), ctx.properties());

	/** 内置：斧（真正 {@link ChasmAxeItem} 子类）。 */
	public static final ChasmItemFactory AXE_FACTORY =
		ctx -> new ChasmAxeItem(ctx.tier() != null ? ctx.tier() : DEFAULT_AXE_TIER,
			ctx.behavior(), ctx.properties());

	/** 内置：锹（真正 {@link ChasmShovelItem} 子类）。 */
	public static final ChasmItemFactory SHOVEL_FACTORY =
		ctx -> new ChasmShovelItem(ctx.tier() != null ? ctx.tier() : DEFAULT_SHOVEL_TIER,
			ctx.behavior(), ctx.properties());

	/** 内置：锄（真正 {@link ChasmHoeItem} 子类）。 */
	public static final ChasmItemFactory HOE_FACTORY =
		ctx -> new ChasmHoeItem(ctx.tier() != null ? ctx.tier() : DEFAULT_HOE_TIER,
			ctx.behavior(), ctx.properties());

	/** 内置：食物（真正 {@link ChasmFoodItem} 子类，接入原版食用机制）。 */
	public static final ChasmItemFactory FOOD_FACTORY =
		ctx -> new ChasmFoodItem(ctx.behavior(), ctx.properties());

	/**
	 * 内置：护甲（真正 {@link ChasmArmorItem} 子类）。
	 *
	 * <p>需先经 {@code ItemBuilder.armor(...)} 指定材质与部位（{@code armorMaterial/armorType}
	 * 由构建上下文携带）；缺失时快速失败，避免静默产生无效护甲。</p>
	 */
	public static final ChasmItemFactory ARMOR_FACTORY = ctx -> {
		if (ctx.armorMaterial() == null || ctx.armorType() == null) {
			throw new IllegalStateException(
				"护甲类型需先通过 ItemBuilder.armor(material, type) 或 armor(set, type) 指定材质与部位");
		}
		return new ChasmArmorItem(ctx.armorMaterial(), ctx.armorType(),
			ctx.behavior(), ctx.properties());
	};

	/** 内置类型：普通物品。 */
	public static final ItemType NONE =
		ItemType.builder("chasm", "none", NONE_FACTORY).build();

	/** 内置类型：食物（食用机制 + 效果/余物，经 {@code ItemBuilder.food(...)} 自动应用）。 */
	public static final ItemType FOOD =
		ItemType.builder("chasm", "food", FOOD_FACTORY).build();

	/** 内置类型：护甲（自动穿戴 + 套装效果，经 {@code ItemBuilder.armor(...)} 自动应用）。 */
	public static final ItemType ARMOR =
		ItemType.builder("chasm", "armor", ARMOR_FACTORY).build();

	/** 内置类型：剑（横扫/剑类附魔池 + 攻击/攻速默认模板）。 */
	public static final ItemType SWORD =
		ItemType.builder("chasm", "sword", SWORD_FACTORY)
			.tag("c:swords")
			.tag("minecraft:swords")
			.defaultAttribute(Attributes.ATTACK_DAMAGE.value(), 1.0f, EquipmentSlotGroup.MAINHAND)
			.defaultAttribute(Attributes.ATTACK_SPEED.value(), -2.4f, EquipmentSlotGroup.MAINHAND)
			.build();

	/** 内置类型：镐（挖掘 + 攻击/攻速默认模板）。 */
	public static final ItemType PICKAXE =
		ItemType.builder("chasm", "pickaxe", PICKAXE_FACTORY)
			.tag("c:pickaxes")
			.tag("minecraft:pickaxes")
			.defaultAttribute(Attributes.ATTACK_DAMAGE.value(), 1.0f, EquipmentSlotGroup.MAINHAND)
			.defaultAttribute(Attributes.ATTACK_SPEED.value(), -2.8f, EquipmentSlotGroup.MAINHAND)
			.build();

	/** 内置类型：斧（剥皮 + 攻击/攻速默认模板）。 */
	public static final ItemType AXE =
		ItemType.builder("chasm", "axe", AXE_FACTORY)
			.tag("c:axes")
			.tag("minecraft:axes")
			.defaultAttribute(Attributes.ATTACK_DAMAGE.value(), 5.0f, EquipmentSlotGroup.MAINHAND)
			.defaultAttribute(Attributes.ATTACK_SPEED.value(), -3.0f, EquipmentSlotGroup.MAINHAND)
			.build();

	/** 内置类型：锹（铺路 + 攻击/攻速默认模板）。 */
	public static final ItemType SHOVEL =
		ItemType.builder("chasm", "shovel", SHOVEL_FACTORY)
			.tag("c:shovels")
			.tag("minecraft:shovels")
			.defaultAttribute(Attributes.ATTACK_DAMAGE.value(), 1.5f, EquipmentSlotGroup.MAINHAND)
			.defaultAttribute(Attributes.ATTACK_SPEED.value(), -3.0f, EquipmentSlotGroup.MAINHAND)
			.build();

	/** 内置类型：锄（耕地 - 攻速默认模板）。 */
	public static final ItemType HOE =
		ItemType.builder("chasm", "hoe", HOE_FACTORY)
			.tag("c:hoes")
			.tag("minecraft:hoes")
			.defaultAttribute(Attributes.ATTACK_DAMAGE.value(), 0.0f, EquipmentSlotGroup.MAINHAND)
			.defaultAttribute(Attributes.ATTACK_SPEED.value(), -3.0f, EquipmentSlotGroup.MAINHAND)
			.build();

	/** 全局单例。 */
	public static final ChasmTypes INSTANCE = new ChasmTypes();

	static {
		registerInternal(NONE);
		registerInternal(FOOD);
		registerInternal(ARMOR);
		registerInternal(SWORD);
		registerInternal(PICKAXE);
		registerInternal(AXE);
		registerInternal(SHOVEL);
		registerInternal(HOE);
	}

	private final Map<ResourceLocation, ItemType> registry = new ConcurrentHashMap<>();

	private ChasmTypes() {
	}

	/**
	 * 注册一个自定义物品类型，返回构建器；调用 {@link ItemType.Builder#build()} 时
	 * 自动登记到本注册表并输出日志（与 {@code ChasmDamageTypes.register} 同款模式）。
	 *
	 * <pre>{@code
	 * ItemType staff = Chasm.types().register("mymod", "staff",
	 *         ctx -> new ChasmItem(ctx.behavior(), ctx.properties()))
	 *     .tag("chasm:staffs")
	 *     .build();
	 * }</pre>
	 *
	 * @param modId   模组命名空间
	 * @param name    类型注册名
	 * @param factory 原版类映射工厂（法杖可用 {@code ChasmItem::new} 包装，或自定义子类）
	 */
	public ItemType.Builder register(String modId, String name, ChasmItemFactory factory) {
		return ItemType.builder(modId, name, factory)
			.onBuild(ChasmTypes::registerInternal);
	}

	/**
	 * 直接把已 build() 的类型登记到注册表（用 {@link ItemType.Builder#build()} 构建后调用）。
	 */
	public void register(ItemType type) {
		registerInternal(type);
	}

	private static void registerInternal(ItemType type) {
		ResourceLocation id = type.id();
		ChasmTypes.INSTANCE.registry.put(id, type);
		ChasmLogger.info(id.getNamespace(), "注册物品类型 {} (默认属性 {} 条, 标签 {})",
			id, type.defaultAttributes().size(), type.tags());
	}

	/** 按完整 id 查询类型（不存在返回 empty）。 */
	public Optional<ItemType> get(ResourceLocation id) {
		return Optional.ofNullable(registry.get(id));
	}

	/** 按 modId + name 查询类型。 */
	public Optional<ItemType> get(String modId, String name) {
		return get(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/** 按字符串 id 查询（含 ":" 自动判别命名空间，如 "chasm:sword" 或 "sword"）。 */
	public Optional<ItemType> get(String id) {
		ResourceLocation rl = id.indexOf(':') >= 0
			? ResourceLocation.parse(id)
			: ResourceLocation.fromNamespaceAndPath("chasm", id);
		return get(rl);
	}

	/** 按 id 查询，不存在抛出异常。 */
	public ItemType getOrThrow(ResourceLocation id) {
		ItemType type = registry.get(id);
		if (type == null) {
			throw new IllegalArgumentException("未知物品类型: " + id);
		}
		return type;
	}

	/** 已注册类型总数（含内置 NONE/SWORD）。 */
	public int size() {
		return registry.size();
	}

	/** 全部已注册类型（只读视图）。 */
	public Collection<ItemType> all() {
		return Collections.unmodifiableCollection(registry.values());
	}

	/**
	 * Chasm 剑类型的默认 Tier：中上附魔等级、可铁锭修复，攻击伤害加成不参与（伤害由
	 * {@code ItemBuilder.attackDamage()} 的属性修饰符驱动）。
	 */
	private static final class ChasmSwordTier implements Tier {

		@Override
		public int getUses() {
			return Tiers.IRON.getUses();
		}

		@Override
		public float getSpeed() {
			return Tiers.IRON.getSpeed();
		}

		@Override
		public float getAttackDamageBonus() {
			return 0.0f; // 伤害完全由自定义属性修饰符决定
		}

		@Override
		public net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> getIncorrectBlocksForDrops() {
			return Tiers.IRON.getIncorrectBlocksForDrops();
		}

		@Override
		public int getEnchantmentValue() {
			return 15; // 优秀附魔等级：剑类附魔（含横扫之刃）可正常附魔
		}

		@Override
		public Ingredient getRepairIngredient() {
			return Ingredient.of(Items.IRON_INGOT);
		}
	}

	/**
	 * Chasm 工具类型的默认 Tier（镐/斧/锹/锄共用）：中上附魔等级、可铁锭修复，
	 * 提供原版铁级的挖掘等级。攻击伤害完全由 {@code ItemBuilder} 属性修饰符驱动。
	 */
	private static final class ChasmToolTier implements Tier {

		@Override
		public int getUses() {
			return Tiers.IRON.getUses();
		}

		@Override
		public float getSpeed() {
			return Tiers.IRON.getSpeed();
		}

		@Override
		public float getAttackDamageBonus() {
			return 0.0f; // 挖掘工具的攻击伤害由自定义属性修饰符决定
		}

		@Override
		public net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> getIncorrectBlocksForDrops() {
			return Tiers.IRON.getIncorrectBlocksForDrops();
		}

		@Override
		public int getEnchantmentValue() {
			return 15;
		}

		@Override
		public Ingredient getRepairIngredient() {
			return Ingredient.of(Items.IRON_INGOT);
		}
	}
}