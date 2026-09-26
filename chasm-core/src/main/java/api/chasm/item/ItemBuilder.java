package api.chasm.item;

import api.chasm.data.ChasmData;
import api.chasm.enchantment.EnchantmentConfig;
import api.chasm.item.context.AttackContext;
import api.chasm.item.context.KeyContext;
import api.chasm.item.context.UseContext;
import api.chasm.item.event.ChasmItemEvents;
import api.chasm.item.event.ItemEventKey;
import api.chasm.log.ChasmLogger;
import api.chasm.template.ArmorSetSpec;
import api.chasm.template.FoodTemplate;
import api.chasm.template.KillReward;
import api.chasm.template.WeaponTemplate;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 物品声明 Builder（玩法行为的链式组合 + 资源声明）。
 *
 * <p>用法：</p>
 *
 * <pre>{@code
 * Chasm.item()
 *     .maxStackSize(1).durability(1200)
 *     .name("Mana Sword")                       // 显示名（自动生成语言文件）
 *     .texture("minecraft:item/diamond_sword")  // 贴图（自动生成模型）
 *     .onRightClick(ctx -> ctx.swingArm())
 *     .register()
 * }</pre>
 */
public final class ItemBuilder {

	private final Item.Properties properties = new Item.Properties();
	private final List<Consumer<UseContext>> rightClickHandlers = new ArrayList<>();
	private final List<Consumer<AttackContext>> attackHandlers = new ArrayList<>();
	private final List<DataComponentType<?>> defaultComponentTypes = new ArrayList<>();
	private final List<Object> defaultComponentValues = new ArrayList<>();
	// 延迟绑定：Record 类 → 默认值，运行时（getDefaultInstance）才解析类型，规避静态初始化时序
	private final List<Class<?>> deferredComponentClasses = new ArrayList<>();
	private final List<Object> deferredComponentValues = new ArrayList<>();
	private String displayName;
	/** 按栈动态命名（可空）：同一物品的不同组件状态显示不同名字。 */
	private java.util.function.Function<net.minecraft.world.item.ItemStack, net.minecraft.network.chat.Component> nameProvider;
	private String texture;
	/** 物品模型是否由本模组自带（true = 不生成，避免与自带模型同路径冲突）。 */
	private boolean autoModel = true;
	private ResourceLocation useTraitId;
	private ResourceLocation attackTraitId;
	private ResourceLocation inventoryTraitId;
	/** 击杀奖励 id（第十四步模板层，写入 KILL_REWARD_ID 组件，击杀时执行）。 */
	private ResourceLocation killRewardId;
	/** 护甲套装 id（第十四步模板层，写入 ARMOR_SET_ID 组件，tick 时触发套装效果）。 */
	private ResourceLocation armorSetId;
	/** 护甲材质/部位（仅护甲类型使用，经 armor(...) 指定）。 */
	private Holder<ArmorMaterial> armorMaterial;
	private ArmorItem.Type armorType;
	/** 是否显式声明过耐久（armor 自动派生耐久时避让）。 */
	private boolean durabilitySet;
	/** 声明的基础攻击力（普通攻击伤害），默认 1.0f 与原版一致。 */
	private float attackDamage = 1.0f;
	/** 任意属性声明（含 attackDamage 转成的修饰符在内，统一合并进 ATTRIBUTE_MODIFIERS）。 */
	private final List<AttributeDeclaration> attributeDeclarations = new ArrayList<>();
	/** 物品类型（决定原版类映射：普通物品/剑等）；null 表示默认普通物品。 */
	private ItemType type;
	/** 可选原版 Tier（工具类类型如 SWORD 使用，null 用类型默认）。 */
	private Tier tier;
	/** 待注册的附带附魔（自带附魔，写入 ENCHANTMENTS 组件）。 */
	private final List<EnchantmentBinding> pendingEnchantments = new ArrayList<>();
	/** 待绑定的自定义按键处理器（keyId → 回调），register 时写入行为载体（第十二步）。 */
	private final List<KeyBindingDecl> keyBindingDecls = new ArrayList<>();

	/** 事件种类 id → 处理器 id 列表（作为默认组件随新实例携带）。 */
	private final Map<ResourceLocation, List<ResourceLocation>> eventHandlerIds = new LinkedHashMap<>();

	/** 仅由 {@link api.chasm.Chasm#item()} 创建；creator 可后续自定义实现（暂留扩展位）。 */
	public ItemBuilder() {
	}

	/**
	 * 指定物品类型（{@link api.chasm.item.ChasmTypes} 内置 {@code SWORD/NONE}，
	 * 或自定义注册的类型）。决定此物品原版类映射（如剑 → 横扫/剑类附魔池）。
	 *
	 * <p>类型提供的默认属性模板会自动合并到物品上（物品自身声明可覆盖同 id 的默认修饰符）。</p>
	 */
	public ItemBuilder type(ItemType type) {
		this.type = type;
		return this;
	}

	/**
	 * 指定原版 Tier（仅对工具类类型如 SWORD 生效；未指定时用类型内置默认 Tier）。
	 */
	public ItemBuilder tier(Tier tier) {
		this.tier = tier;
		return this;
	}

	/**
	 * 给物品<b>自带</b>一个附魔（随默认实例写入 {@code ENCHANTMENTS} 组件，类似原版附魔书）。
	 *
	 * <p>原版附魔可直接传 {@code Enchantments.XXX}（其本身即 {@code Holder<Enchantment>}）；</p>
	 * <pre>{@code
	 * Chasm.item().type(ChasmTypes.SWORD)
	 *     .enchant(Enchantments.FIRE_ASPECT, 2)
	 *     .register();
	 * }</pre>
	 */
	public ItemBuilder enchant(Holder<Enchantment> ench, int level) {
		pendingEnchantments.add(new EnchantmentBinding(ench, null, level));
		return this;
	}

	/**
	 * 给物品<b>自带</b>一个附魔（经注册键，运行时解析 {@code Holder<Enchantment>}，用于自定义附魔）。
	 */
	public ItemBuilder enchant(ResourceKey<Enchantment> ench, int level) {
		pendingEnchantments.add(new EnchantmentBinding(null, ench, level));
		return this;
	}

	/** 给物品自带附魔（经自定义附魔声明，见 {@link api.chasm.enchantment.EnchantmentDeclaration}）。 */
	public ItemBuilder enchant(api.chasm.enchantment.EnchantmentDeclaration decl, int level) {
		return enchant(decl.key(), level);
	}

	/**
	 * 把该附魔加入本物品所绑定类型的附魔池（非宝藏，附魔台可见）。
	 *
	 * <p>需先声明 {@code .type(...)}。效果等同在类型构建时调用 {@code .enchantment(...)}，
	 * 但允许在物品声明处就地追加。</p>
	 */
	public ItemBuilder enchantable(ResourceKey<Enchantment> ench, int minLevel, int maxLevel, int weight) {
		return enchantable(ench, minLevel, maxLevel, weight, false);
	}

	/** 把该附魔加入本物品所绑定类型的附魔池（可指定 treasureOnly）。 */
	public ItemBuilder enchantable(ResourceKey<Enchantment> ench, int minLevel, int maxLevel,
		int weight, boolean treasureOnly) {
		requireType("enchantable");
		type.addEnchantmentToPool(new EnchantmentConfig(ench, minLevel, maxLevel, weight, treasureOnly));
		ChasmLogger.info(ench.location().getNamespace(), "物品向类型 {} 附魔池加入 {} ({}~{} 级, weight={}, treasure={})",
			type.id(), ench.location(), minLevel, maxLevel, weight, treasureOnly);
		return this;
	}

	/** 把该附魔加入本物品所绑定类型的附魔池（经自定义附魔声明）。 */
	public ItemBuilder enchantable(api.chasm.enchantment.EnchantmentDeclaration decl,
		int minLevel, int maxLevel, int weight) {
		requireType("enchantable");
		type.addEnchantmentToPool(new EnchantmentConfig(decl.key(),
			minLevel, maxLevel, weight, decl.treasure()));
		ChasmLogger.info(decl.location().getNamespace(), "物品向类型 {} 附魔池加入 {} ({}~{} 级, weight={})",
			type.id(), decl.location(), minLevel, maxLevel, weight);
		return this;
	}

	private void requireType(String caller) {
		if (type == null) {
			throw new IllegalStateException(caller + "() 需先声明 .type(...) 绑定一个物品类型");
		}
	}

	/** 最大堆叠数（默认 64）。 */
	public ItemBuilder maxStackSize(int size) {
		properties.stacksTo(size);
		return this;
	}

	/** 耐久度（默认无限）。 */
	public ItemBuilder durability(int durability) {
		properties.durability(durability);
		this.durabilitySet = true;
		return this;
	}

	/**
	 * 声明基础攻击力（总攻击伤害，含原版基础值）。默认 1.0f 与原版一致。
	 *
	 * <p>内部转为对该属性的主手 {@code +total-base} 属性修饰符，其中 {@code base} 取
	 * {@link Attributes#ATTACK_DAMAGE} 的真实默认值（1.21.1 为 2.0），由原版
	 * {@code Player.attack} 结算普通伤害；并可经 {@code ChasmItem.getAttackDamage()}
	 * 读取，供特质计算魔法伤害差额。</p>
	 */
	public ItemBuilder attackDamage(float damage) {
		this.attackDamage = damage;
		float base = (float) net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.value().getDefaultValue();
		float modifier = damage - base;
		if (modifier != 0.0f) {
			attributeDeclarations.add(AttributeDeclaration.mainHand(
				net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.value(),
				Item.BASE_ATTACK_DAMAGE_ID, modifier));
		}
		return this;
	}

	/**
	 * 声明攻击速度（总攻击速度，含原版基础 4.0）写入主手 {@code ATTACK_SPEED}。
	 *
	 * <p>例如声明 total=0.5 → 修饰符 -3.5。原版计算：{@code 4.0 + modifier}。</p>
	 */
	public ItemBuilder attackSpeed(float total) {
		api.chasm.attribute.ChasmAttackSpeed.requireSaneTotal(total, "物品攻击速度声明");
		return attribute(Attributes.ATTACK_SPEED.value(), total - 4.0f, EquipmentSlotGroup.MAINHAND);
	}

	/** 声明护甲值（{@code ARMOR}，任意生效槽位，默认任意）。 */
	public ItemBuilder armor(float amount) {
		return attribute(Attributes.ARMOR.value(), amount, EquipmentSlotGroup.ANY);
	}

	/** 声明护甲韧性（{@code ARMOR_TOUGHNESS}）。 */
	public ItemBuilder armorToughness(float amount) {
		return attribute(Attributes.ARMOR_TOUGHNESS.value(), amount, EquipmentSlotGroup.ANY);
	}

	/** 声明移动速度修正（{@code MOVEMENT_SPEED}，ADD_MULTIPLIED_BASE，主手）。 */
	public ItemBuilder movementSpeed(float amount) {
		return attribute(Attributes.MOVEMENT_SPEED.value(), amount,
			AttributeModifier.Operation.ADD_MULTIPLIED_BASE, EquipmentSlotGroup.MAINHAND);
	}

	/** 声明击退抗性（{@code KNOCKBACK_RESISTANCE}，ADD_VALUE，任意槽位）。 */
	public ItemBuilder knockbackResistance(float amount) {
		return attribute(Attributes.KNOCKBACK_RESISTANCE.value(), amount, EquipmentSlotGroup.ANY);
	}

	/** 声明一个属性修饰符（ADD_VALUE，指定生效槽位组）。 */
	public ItemBuilder attribute(Attribute attribute, float amount, EquipmentSlotGroup slot) {
		return attribute(attribute,
			ResourceLocation.fromNamespaceAndPath("chasm", "item_" + attributeId(attribute) + "_" + attributeDeclarations.size()),
			amount, AttributeModifier.Operation.ADD_VALUE, slot);
	}

	/** 声明一个属性修饰符（指定运算与生效槽位组）。 */
	public ItemBuilder attribute(Attribute attribute, float amount,
		AttributeModifier.Operation operation, EquipmentSlotGroup slot) {
		return attribute(attribute,
			ResourceLocation.fromNamespaceAndPath("chasm", "item_" + attributeId(attribute) + "_" + attributeDeclarations.size()),
			amount, operation, slot);
	}

	/** 声明一个属性修饰符（完整参数）。 */
	public ItemBuilder attribute(Attribute attribute, ResourceLocation modifierId, float amount,
		AttributeModifier.Operation operation, EquipmentSlotGroup slot) {
		attributeDeclarations.add(new AttributeDeclaration(attribute, modifierId, amount, operation, slot));
		return this;
	}

	private static String attributeId(Attribute attribute) {
		// 反查属性 id（尽量用其内置 id），取最后一段作为可读名
		String s = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(attribute));
		int idx = s.indexOf(':');
		return idx >= 0 ? s.substring(idx + 1) : s;
	}

	/** 显示名（自动生成语言文件，如 "Mana Sword"）。 */
	/**
	 * **按栈动态命名**（可选，覆盖语言文件里的静态名）。
	 *
	 * <pre>{@code
	 * Chasm.item().name("宝石")
	 *     .nameProvider(stack -> purityOf(stack).name("战斗宝石"))
	 *     .register();
	 * }</pre>
	 *
	 * <p>返回值可以是带颜色/样式的 Component（原版名会用该样式渲染）。
	 * 返回 null 时回落到静态名。</p>
	 */
	public ItemBuilder nameProvider(
		java.util.function.Function<net.minecraft.world.item.ItemStack, net.minecraft.network.chat.Component> provider) {
		this.nameProvider = provider;
		return this;
	}

	public ItemBuilder name(String name) {
		this.displayName = name;
		return this;
	}

	/** 贴图资源路径（自动生成模型，如 "minecraft:item/diamond_sword"）。 */
	public ItemBuilder texture(String texture) {
		this.texture = texture;
		return this;
	}

	/**
	 * **自带模型**：本模组在 {@code assets/<ns>/models/item/<id>.json} 里提供真实模型，
	 * DataGen 不要自动生成。
	 *
	 * <p>为什么必须显式声明：自动生成的占位模型与自带模型同路径，两者同时在资源里时
	 * {@code processResources} 会因"重复条目"直接失败（两个真相源，不能靠 duplicatesStrategy 盖住）。</p>
	 */
	public ItemBuilder ownModel() {
		this.autoModel = false;
		return this;
	}

	/** 玩家右键使用时触发的玩法行为（可链式叠加多个）。 */
	public ItemBuilder onRightClick(Consumer<UseContext> handler) {
		rightClickHandlers.add(handler);
		return this;
	}

	/** 用物品攻击实体时触发的玩法行为（可链式叠加多个）。 */
	public ItemBuilder onAttack(Consumer<AttackContext> handler) {
		attackHandlers.add(handler);
		return this;
	}

	/**
	 * 绑定一个物品事件处理器（{@link api.chasm.item.event.ChasmItemEvents} 开放事件总线）。
	 *
	 * <p>写入 {@link ChasmData#EVENT_HANDLERS} 默认组件，因此该物品的每个新实例都携带此处理器；
	 * 运行时由内置/第三方事件源（受击、命中、挖方块、useOn、弹射物命中、盾挡…）触发。</p>
	 *
	 * @param event     事件种类（内置如 {@link api.chasm.item.event.ChasmItemEventKeys#ON_HIT}，或自注册）
	 * @param handlerId 处理器 id（须已用 {@code ChasmItemEvents.handler(...)} 注册实现）
	 */
	public ItemBuilder eventHandler(ItemEventKey<?> event, ResourceLocation handlerId) {
		eventHandlerIds.computeIfAbsent(event.id(), k -> new ArrayList<>()).add(handlerId);
		return this;
	}

	/**
	 * 为物品绑定数据组件默认值（每个默认实例都会携带该值）。
	 *
	 * <p>内部写入 {@code Item.Properties.component()}，并记录到 Builder 供
	 * {@link api.chasm.registry.ChasmRegistrar} 在注册时输出结构化日志。</p>
	 *
	 * @param type  数据组件类型（经 {@code ChasmData} 注册过）
	 * @param value 默认值
	 */
	public <T> ItemBuilder component(DataComponentType<T> type, T value) {
		properties.component(type, value);
		defaultComponentTypes.add(type);
		defaultComponentValues.add(value);
		return this;
	}

	/**
	 * 为物品绑定 Record 数据组件默认值（延迟到运行时解析）。
	 *
	 * <p>解决静态初始化时序死穴：{@code @DataComponent} Record 的组件类型由
	 * {@code onInitialize} 的 {@code ChasmRegistrar.scan} 注册，晚于物品静态字段初始化。
	 * 本重载不立即解析类型，而是把 {@code recordClass + value} 记入 Builder；真正生效在
	 * {@code ChasmItem.getDefaultInstance()} 运行时（此时类型已注册）延迟解析并写入。</p>
	 *
	 * @param recordClass Record 类（须经 {@code @DataComponent} 声明、由 scan 注册）
	 * @param value       默认值
	 */
	public <T> ItemBuilder component(Class<T> recordClass, T value) {
		deferredComponentClasses.add(recordClass);
		deferredComponentValues.add(value);
		return this;
	}

	/**
	 * 绑定右键使用特质（注册于 {@link api.chasm.trait.ChasmTraits} 的特质 id）。
	 *
	 * <p>运行时由 {@code ChasmItem.use} 从物品栈读取该 id、O(1) 查回特质并执行，
	 * 替代 List&lt;Consumer&gt; 遍历。</p>
	 */
	public ItemBuilder useTrait(ResourceLocation traitId) {
		this.useTraitId = traitId;
		return this;
	}

	/** 绑定右键使用特质（便捷重载：modId + name）。 */
	public ItemBuilder useTrait(String modId, String name) {
		return useTrait(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/**
	 * 绑定攻击实体特质（注册于 {@link api.chasm.trait.ChasmTraits} 的特质 id）。
	 */
	public ItemBuilder attackTrait(ResourceLocation traitId) {
		this.attackTraitId = traitId;
		return this;
	}

	/** 绑定攻击实体特质（便捷重载：modId + name）。 */
	public ItemBuilder attackTrait(String modId, String name) {
		return attackTrait(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/**
	 * 绑定物品栏 tick 特质（注册于 {@link api.chasm.trait.ChasmTraits} 的特质 id）。
	 *
	 * <p>运行时由 {@code ChasmItem.inventoryTick} 从物品栈读取该 id、O(1) 查回特质并执行，
	 * 用于持续效果（如持有物品时每秒回蓝）。</p>
	 */
	public ItemBuilder inventoryTrait(ResourceLocation traitId) {
		this.inventoryTraitId = traitId;
		return this;
	}

	/** 绑定物品栏 tick 特质（便捷重载：modId + name）。 */
	public ItemBuilder inventoryTrait(String modId, String name) {
		return inventoryTrait(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/**
	 * 应用食物模板（第十四步 T-A4）：写入原版 {@code DataComponents.FOOD} 组件。
	 *
	 * <p>自动把类型切换为 {@link ChasmTypes#FOOD}（实例化为 {@link ChasmFoodItem}，
	 * 食用动画/营养/效果/余物归还全部由原版机制接管），除非已显式指定其他类型。
	 * 营养/饱和/效果/余物/光泽等声明见 {@link FoodTemplate}。</p>
	 */
	public ItemBuilder food(FoodTemplate template) {
		if (type == null) {
			type = ChasmTypes.FOOD;
		}
		FoodProperties.Builder builder = new FoodProperties.Builder()
			.nutrition(template.nutrition())
			.saturationModifier(template.saturationMod());
		if (template.canAlwaysEat()) {
			builder.alwaysEdible();
		}
		if (template.fast()) {
			builder.fast();
		}
		if (template.remainder() != null) {
			builder.usingConvertsTo(template.remainder());
		}
		for (FoodTemplate.FoodEffect e : template.effects()) {
			builder.effect(new MobEffectInstance(e.effect(), e.durationTicks(), e.amplifier()), e.chance());
		}
		properties.food(builder.build());
		ChasmLogger.info("chasm", "应用食物模板（营养={}, 效果 {} 条, 余物={}）到物品",
			template.nutrition(), template.effects().size(), template.remainder());
		return this;
	}

	/** 便捷：仅营养 + 饱和的无效果食物。 */
	public ItemBuilder food(int nutrition, float saturation) {
		return food(api.chasm.template.ChasmTemplates.INSTANCE.food()
			.nutrition(nutrition).saturation(saturation).build());
	}

	/**
	 * 绑定击杀奖励（第十四步 T-A1）：玩家用本物品击杀实体时，服务端执行奖励。
	 *
	 * @param rewardId 经 {@code Chasm.templates().killReward(...)} 注册的击杀奖励 id
	 */
	public ItemBuilder killReward(ResourceLocation rewardId) {
		this.killRewardId = rewardId;
		return this;
	}

	/** 绑定击杀奖励（便捷重载：直接传已构建的奖励对象）。 */
	public ItemBuilder killReward(KillReward reward) {
		return killReward(reward.id());
	}

	/**
	 * 应用武器能力模板（第十四步 T-A1）：一次性绑定命中特质 + 击杀奖励 + 默认属性。
	 *
	 * <pre>{@code
	 * WeaponTemplate GREED = Chasm.templates().weapon("mymod", "greed")
	 *     .onHit(ctx -> ctx.target().setSecondsOnFire(2))
	 *     .killDrop(Items.EMERALD, 3, 8, 1.0f)
	 *     .build();
	 * Chasm.item().template(GREED)...register();
	 * }</pre>
	 */
	public ItemBuilder template(WeaponTemplate template) {
		if (template.attackDamage() != 0.0f) {
			attackDamage(template.attackDamage());
		}
		if (template.attackSpeed() != 0.0f) {
			attackSpeed(template.attackSpeed());
		}
		if (template.hitTraitId() != null) {
			attackTrait(template.hitTraitId());
		}
		if (template.killRewardId() != null) {
			killReward(template.killRewardId());
		}
		ChasmLogger.info(template.id().getNamespace(), "应用武器模板 {} 到物品", template.id());
		return this;
	}

	/**
	 * 声明一件护甲（第十四步 T-A2）：指定护甲套装模板与部位。
	 *
	 * <p>自动把类型切换为 {@link ChasmTypes#ARMOR}（实例化为 {@link ChasmArmorItem}，
	 * 保留自动穿戴/材质属性/套装效果）；若未显式声明耐久，则由套装耐久系数按部位自动派生。</p>
	 *
	 * @param set  护甲套装模板（经 {@code Chasm.templates().armorSet(...)} 声明）
	 * @param type 部位（HELMET/CHESTPLATE/LEGGINGS/BOOTS）
	 */
	public ItemBuilder armor(ArmorSetSpec set, ArmorItem.Type type) {
		this.type = ChasmTypes.ARMOR;
		this.armorSetId = set.id();
		this.armorMaterial = set.material();
		this.armorType = type;
		if (!durabilitySet) {
			properties.durability(type.getDurability(set.durabilityFactor()));
		}
		return this;
	}

	/**
	 * 声明一件无套装效果的护甲（直接复用原版/自定义材质）。
	 *
	 * @param material 护甲材质（如 {@code ArmorMaterials.IRON}）
	 * @param type     部位（HELMET/CHESTPLATE/LEGGINGS/BOOTS）
	 */
	public ItemBuilder armor(Holder<ArmorMaterial> material, ArmorItem.Type type) {
		this.type = ChasmTypes.ARMOR;
		this.armorMaterial = material;
		this.armorType = type;
		return this;
	}

	/**
	 * 绑定一个自定义按键（第十二步）：玩家手持本物品按下该键时触发回调（服务端主线程）。
	 *
	 * <p>按键须先经 {@code Chasm.keys().register(...)} 获得句柄；客户端会自动把按键
	 * 显示到原版「选项 -&gt; 按键控制」列表，玩家可自由改键。</p>
	 *
	 * <pre>{@code
	 * Chasm.item().onKeyPress(Chasm.keys().register("mymod", "magic_blast", 79, "Magic Blast"),
	 *     ctx -> ctx.player().sendSystemMessage(Component.literal("按 O 发射魔法弹！"))).register();
	 * }</pre>
	 */
	public ItemBuilder onKeyPress(ChasmKeyBinding key, Consumer<KeyContext> handler) {
		return onKeyPress(key.id(), handler);
	}

	/** 绑定一个自定义按键（经按键 id 直接指定，须与 {@code Chasm.keys()} 注册的 id 一致）。 */
	public ItemBuilder onKeyPress(ResourceLocation keyId, Consumer<KeyContext> handler) {
		keyBindingDecls.add(new KeyBindingDecl(keyId, handler));
		return this;
	}

	/** 构建并返回物品实例（注册由 {@code @Register} 字段 + 扫描器完成）。 */
	public Item register() {
		// 1) 将绑定的特质 id 作为默认数据组件写入 Item.Properties，随默认实例携带；
		//    ChasmData 的静态初始化通常已由上层组件注册（如 SOULS）触发，此处置空逻辑安全。
		if (useTraitId != null) {
			properties.component(ChasmData.USE_TRAIT_ID, useTraitId);
			ChasmLogger.info(useTraitId.getNamespace(), "绑定使用特质 {} 到物品", useTraitId);
		}
		if (attackTraitId != null) {
			properties.component(ChasmData.ATTACK_TRAIT_ID, attackTraitId);
			ChasmLogger.info(attackTraitId.getNamespace(), "绑定攻击特质 {} 到物品", attackTraitId);
		}
		if (inventoryTraitId != null) {
			properties.component(ChasmData.INVENTORY_TICK_TRAIT_ID, inventoryTraitId);
			ChasmLogger.info(inventoryTraitId.getNamespace(), "绑定物品栏 tick 特质 {} 到物品", inventoryTraitId);
		}
		if (killRewardId != null) {
			properties.component(ChasmData.KILL_REWARD_ID, killRewardId);
			ChasmLogger.info(killRewardId.getNamespace(), "绑定击杀奖励 {} 到物品", killRewardId);
		}
		if (armorSetId != null) {
			properties.component(ChasmData.ARMOR_SET_ID, armorSetId);
			ChasmLogger.info(armorSetId.getNamespace(), "绑定护甲套装 {} 到物品", armorSetId);
		}
		if (!eventHandlerIds.isEmpty()) {
			Map<ResourceLocation, List<ResourceLocation>> copy = new LinkedHashMap<>();
			eventHandlerIds.forEach((eventId, handlers) ->
				copy.put(eventId, java.util.Collections.unmodifiableList(new ArrayList<>(handlers))));
			properties.component(ChasmData.EVENT_HANDLERS, java.util.Collections.unmodifiableMap(copy));
			ChasmLogger.info(type != null ? type.id().getNamespace() : "chasm",
				"绑定 {} 个事件种类的处理器到物品", eventHandlerIds.size());
		}

		// 2) 合并属性修饰符：类型默认属性模板在前，物品自身声明在后；同 (属性+槽位组+运算)
		//    的声明以物品覆盖类型默认（丢弃类型默认项），避免重复累加。
		//    统一写入 ATTRIBUTE_MODIFIERS 组件 → 工具提示正确显示、整合包/重铸系统可读取真实属性。
		List<AttributeDeclaration> combined = new ArrayList<>();
		if (type != null) {
			Set<AttributeKey> own = toKeySet(attributeDeclarations);
			for (AttributeDeclaration decl : type.defaultAttributes()) {
				if (!own.contains(AttributeKey.of(decl))) {
					combined.add(decl);
				}
			}
		}
		combined.addAll(attributeDeclarations);
		if (!combined.isEmpty()) {
			ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
			int written = 0;
			for (AttributeDeclaration decl : combined) {
				// 必须是注册表 Holder：direct Holder 无法序列化，会在保存/同步时崩（见 ChasmItemModifiers 注释）
				Holder<Attribute> holder = ChasmItemModifiers.holder(decl.attribute());
				if (holder == null) {
					ChasmLogger.warn(type != null ? type.id().getNamespace() : "chasm",
						"属性 {} 未注册，已跳过修饰符 {}", decl.attribute(), decl.modifierId());
					continue;
				}
				builder.add(holder,
					new AttributeModifier(decl.modifierId(), decl.amount(), decl.operation()), decl.slot());
				written++;
			}
			if (written > 0) {
				properties.component(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
			}
		}

		// 3) 组装共享行为载体。
		ChasmItemBehavior behavior = new ChasmItemBehavior(
			new ArrayList<>(rightClickHandlers), new ArrayList<>(attackHandlers),
			defaultComponentTypes, defaultComponentValues,
			deferredComponentClasses, deferredComponentValues,
			displayName, texture, autoModel, attackDamage);
		// 记录物品绑定的类型（供运行时反查：附魔池注入、栏位校验等）
		behavior.setItemType(type);
		// 按栈动态命名（宝石纯度/附魔等级这类"同名物品不同名"）
		behavior.setNameProvider(nameProvider);

		// 3.4) 应用自定义按键处理器绑定。
		for (KeyBindingDecl decl : keyBindingDecls) {
			behavior.addKeyHandler(decl.keyId(), decl.handler());
			ChasmLogger.info(decl.keyId().getNamespace(), "绑定自定义按键 {} 到物品", decl.keyId());
		}

		// 3.5) 应用附带附魔（自带附魔 → ENCHANTMENTS 组件，运行时解析 Holder）。
		for (EnchantmentBinding binding : pendingEnchantments) {
			if (binding.holder() != null) {
				behavior.addEnchantment(binding.holder(), binding.level());
			} else if (binding.key() != null) {
				behavior.addEnchantment(binding.key(), binding.level());
			}
			ResourceLocation enchId = binding.key() != null ? binding.key().location()
				: (binding.holder() == null ? null : binding.holder().unwrapKey()
					.map(k -> k.location()).orElse(null));
			ChasmLogger.info(type != null ? type.id().getNamespace() : "chasm",
				"物品自带附魔 {} {} 级", enchId, binding.level());
		}

		// 4) 交给类型的原版类映射工厂实例化（含类型默认 Tier 透传 + 护甲材质/部位透传）。
		ChasmItemFactory factory = type != null ? type.factory()
			: api.chasm.item.ChasmTypes.NONE.factory();
		return factory.create(new ChasmItemBuildContext(behavior, properties, tier, armorMaterial, armorType));
	}

	/** 把所有属性声明归一化为 key 集合，用于判断物品是否在 (属性+槽位组+运算) 维度覆盖了默认模板。 */
	private static Set<AttributeKey> toKeySet(List<AttributeDeclaration> decls) {
		Set<AttributeKey> keys = new HashSet<>();
		for (AttributeDeclaration d : decls) {
			keys.add(AttributeKey.of(d));
		}
		return keys;
	}

	/** (属性 + 槽位组 + 运算) 的去重键。 */
	private record AttributeKey(Attribute attribute, EquipmentSlotGroup slot,
		AttributeModifier.Operation operation) {
		static AttributeKey of(AttributeDeclaration d) {
			return new AttributeKey(d.attribute(), d.slot(), d.operation());
		}
	}

	/** 待注册的附带附魔绑定（holder 与 key 二选一）。 */
	private record EnchantmentBinding(Holder<Enchantment> holder, ResourceKey<Enchantment> key, int level) {
	}

	/** 待绑定的自定义按键声明（keyId + 处理器）。 */
	private record KeyBindingDecl(ResourceLocation keyId, Consumer<KeyContext> handler) {
	}
}