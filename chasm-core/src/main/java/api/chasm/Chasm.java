package api.chasm;

import api.chasm.context.ChasmContextRegistry;
import api.chasm.context.ModContext;
import api.chasm.item.ItemBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Chasm 2.0 统一入口（声明式玩法 API 的核心上下文）。
 *
 * <p>遵循一个心智模型：</p>
 *
 * <pre>
 * 声明（@Register / @ChasmMod） → 行为（Builder） → 数据（@DataComponent） → 注册（自动） → 暴露（可扩展）
 * </pre>
 *
 * <p>统一能力：</p>
 * <ul>
 *   <li>声明：{@link #item()} 构建物品（配套 {@link api.chasm.registry.Register @Register} 注解）</li>
 *   <li>隔离：{@link #mod(String)} 获取任一模组的独立上下文（按 modId 隔离）</li>
 *   <li>扩展：拿到目标模组的物品控制台，追加/覆写行为（对闭源模组同样有效）</li>
 * </ul>
 */
public final class Chasm {

	public static final Logger LOGGER = LoggerFactory.getLogger("chasm");

	private Chasm() {
	}

	/** 创建一个物品声明。 */
	public static ItemBuilder item() {
		return new ItemBuilder();
	}

	/**
	 * 获取指定模组的隔离上下文（不存在则创建）。
	 *
	 * <p>用于跨模组扩展：{@code Chasm.mod("target").item("sword").appendRightClick(...)}。</p>
	 *
	 * @param modId 目标模组命名空间
	 */
	public static ModContext mod(String modId) {
		return ChasmContextRegistry.get(modId);
	}

	/** 所有模组上下文（全局聚合视图）。 */
	public static Collection<ModContext> mods() {
		return ChasmContextRegistry.all();
	}

	/**
	 * 数据组件访问器（类型安全，自动 Codec）。
	 *
	 * <p>用法：</p>
	 * <pre>{@code
	 * Chasm.data().get(stack, ManaData.class);        // 读取
	 * Chasm.data().set(stack, ManaData.class, mana);  // 写入
	 * }</pre>
	 */
	public static api.chasm.data.ChasmData data() {
		return api.chasm.data.ChasmData.INSTANCE;
	}

	/**
	 * 配方声明（声明式合成，DataGen 自动生成配方 json）。
	 *
	 * <p>用法（onInitialize 中）：</p>
	 * <pre>{@code
	 * Chasm.recipe("mana_sword")
	 *     .shaped(" A ", " A ", " S ", 'A', Items.AMETHYST_SHARD, 'S', Items.STICK)
	 *     .result(MANA_SWORD)
	 *     .register();
	 * }</pre>
	 *
	 * @param id 配方注册名（不含命名空间）
	 */
	/**
	 * 创造模式标签页（**本轮新增**）：一个模组一个标签页，自动收录自己的物品，免去天天敲 /give。
	 *
	 * <pre>{@code
	 * Chasm.creativeTab("mymod", "main").title("Chasm 示例").icon(MY_ITEM).autoAll().register();
	 * }</pre>
	 *
	 * @param modId 模组命名空间（也是自动收录时的过滤命名空间）
	 * @param name  标签页名（{@code modId:name}）
	 */
	public static api.chasm.item.ChasmCreativeTabBuilder creativeTab(String modId, String name) {
		return new api.chasm.item.ChasmCreativeTabBuilder(modId, name);
	}

	public static api.chasm.recipe.RecipeBuilder recipe(String id) {
		return new api.chasm.recipe.RecipeBuilder(id);
	}

	/**
	 * 创建一个方块声明（自动注册 Block + BlockItem，DataGen 自动生成模型/掉落/语言）。
	 *
	 * <p>用法：</p>
	 * <pre>{@code
	 * @Register("mana_crystal")
	 * public static final Block MANA_CRYSTAL = Chasm.block()
	 *     .strength(3.0f, 3.0f).requiresCorrectToolForDrops()
	 *     .name("Mana Crystal")
	 *     .texture("minecraft:block/amethyst_block")
	 *     .onUse(ctx -> ctx.sendMessage("The crystal hums!"))
	 *     .register();
	 * }</pre>
	 */
	public static api.chasm.block.BlockBuilder block() {
		return new api.chasm.block.BlockBuilder();
	}

	/**
	 * 行为特质注册表（声明式行为抽象）。
	 *
	 * <p>用法（onInitialize 中注册特质，再通过 {@code ItemBuilder.useTrait/attackTrait} 绑定到物品）：</p>
	 * <pre>{@code
	 * ResourceLocation useTrait = Chasm.traits().registerUse("mymod", "mana_use", ctx -> {
	 *     ctx.player().heal(1.0f);
	 *     return InteractionResult.SUCCESS;
	 * });
	 * Chasm.item().useTrait(useTrait)...register();
	 * }</pre>
	 */
	public static api.chasm.trait.ChasmTraits traits() {
		return api.chasm.trait.ChasmTraits.INSTANCE;
	}

	/**
	 * 玩家持久化变量（每玩家变量，类型安全、自动持久化）。
	 *
	 * <p>基于 Fabric {@code DataAttachment}，随世界存档保存、玩家重生保留；
	 * 支持任意类型并可选 {@code min}/{@code max} 自动钳制。所有读写应在服务端。</p>
	 *
	 * <p>用法：</p>
	 * <pre>{@code
	 * public static final PlayerVar<Integer> MANA =
	 *     Chasm.playerVar("mymod", "mana", ChasmCodecs.INT)
	 *         .defaultValue(50).min(0).max(100).build();
	 *
	 * MANA.set(player, MANA.get(player) - 5);
	 * }</pre>
	 *
	 * @param modId 模组命名空间
	 * @param name  变量名（与附件 id 关联）
	 * @param codec 编解码器（持久化用）
	 * @param <T>   变量类型
	 */
	public static <T extends Comparable<T>> api.chasm.player.PlayerVar.Builder<T> playerVar(
		String modId, String name, com.mojang.serialization.Codec<T> codec) {
		return api.chasm.player.PlayerVar.builder(modId, name, codec);
	}

	/**
	 * 伤害类型注册表（声明式伤害类型 + 跨模组查询/聚合）。
	 *
	 * <p>用法：</p>
	 * <pre>{@code
	 * // 注册自定义伤害类型（物理/魔法模板开箱即用：ChasmDamageTypes.PHYSICAL / MAGIC）
	 * DamageTypeInfo arcane = Chasm.damageTypes()
	 *     .register("mymod", "arcane_burn", DamageTypeKind.MAGIC)
	 *     .message("was consumed by arcane fire")
	 *     .bypassesArmor(true)
	 *     .tag("magic").tag("fire")
	 *     .build();
	 *
	 * // 查询（跨模组）
	 * Optional<DamageTypeInfo> info = Chasm.damageTypes().get("othermod", "xxx");
	 * }</pre>
	 */
	public static api.chasm.damage.ChasmDamageTypes damageTypes() {
		return api.chasm.damage.ChasmDamageTypes.INSTANCE;
	}

	/**
	 * 属性注册表（内建常用属性 + 自定义属性注册 + 跨模组查询）。
	 *
	 * <p>ItemBuilder 已提供 {@code attackDamage()/attackSpeed()/armor()/armorToughness()/
	 * movementSpeed()/knockbackResistance()/attribute()} 便捷声明，直接写属性到物品；
	 * 本门面用于查询与注册自定义属性：</p>
	 * <pre>{@code
	 * AttributeInfo manaRegen = Chasm.attributes()
	 *     .registerRanged("mymod", "mana_regen", 0.0f, 0.0f, 100.0f);
	 * AttributeInfo atk = Chasm.attributes().get("attack_damage").orElseThrow();
	 * }</pre>
	 */
	public static api.chasm.attribute.ChasmAttributes attributes() {
		return api.chasm.attribute.ChasmAttributes.INSTANCE;
	}

	/**
	 * 物品类型注册表（类型 = 一类物品的公共语义契约：原版类映射/默认属性/标签）。
	 *
	 * <p>内置 {@code SWORD}（真正 {@code SwordItem} 子类，获得横扫/剑类附魔池语义）
	 * 与 {@code NONE}（普通物品）。用法：</p>
	 * <pre>{@code
	 * // 用内置 SWORD 类型创建一把"真正的剑"
	 * @Register("mana_sword")
	 * public static final Item SWORD = Chasm.item()
	 *     .type(ChasmTypes.SWORD)        // ★ 关键：成为 SwordItem 子类
	 *     .attackDamage(10.0f)
	 *     ...
	 *     .register();
	 *
	 * // 注册一个自定义类型（法杖模板），后续物品复用
	 * Chasm.types().register("mymod", "staff",
	 *         ctx -> new ChasmItem(ctx.behavior(), ctx.properties()))
	 *     .tag("chasm:staffs").tag("c:magic")
	 *     .build();
	 *
	 * // 跨模组查询
	 * Optional<ItemType> sword = Chasm.types().get("chasm", "sword");
	 * }</pre>
	 */
	public static api.chasm.item.ChasmTypes types() {
		return api.chasm.item.ChasmTypes.INSTANCE;
	}

	/**
	 * 自定义附魔注册表（data-driven）。
	 *
	 * <p>1.21.1 附魔为动态注册表，必须产生 {@code data/<ns>/enchantment/*.json}
	 * 才能被真正加载。本门面返回的注册表链式配置后 {@code .build()} 生成
	 * {@link api.chasm.enchantment.EnchantmentDeclaration}（静态期安全引用），
	 * DataGen 会自动输出附魔 JSON 与 supported_items 物品标签，使物品类型声明
	 * 的附魔池原生出现在附魔台。</p>
	 *
	 * <pre>{@code
	 * public static final EnchantmentDeclaration MANA_VAMPIRE =
	 *     Chasm.enchantments().register("mymod", "mana_vampire")
	 *         .weight(10).maxLevel(3).slot("mainhand")
	 *         .build();
	 * }</pre>
	 */
	public static api.chasm.enchantment.ChasmEnchantments enchantments() {
		return api.chasm.enchantment.ChasmEnchantments.INSTANCE;
	}

	/**
	 * 声明式界面系统入口（Chasm.gui）。
	 *
	 * <p>用几行链式声明即可得到一个自定义界面（窗口 + 物品格子 + 虚拟按钮 + 打开预填），
	 * 底层 {@link net.minecraft.world.inventory.MenuType} / {@link api.chasm.gui.ChasmMenu} /
	 * 按钮点击网络包全部由框架接管；按钮点击回调在<b>服务端主线程</b>安全执行。</p>
	 *
	 * <pre>{@code
	 * Chasm.gui("mymod", "magic_box")
	 *     .title("魔法盒")
	 *     .size(9, 3)
	 *     .slot(0, 0, 2, 2)                      // 左上角 2x2 物品存储区
	 *     .button("传送", 5, 0, (player, click) ->
	 *         player.teleportTo(player.getX(), player.getY() + 10, player.getZ()))
	 *     .button("治疗", 7, 0, (player, click) -> player.heal(10.0f))
	 *     .onOpen(ctx -> ctx.setItem(0, net.minecraft.world.item.Items.EMERALD, 5))
	 *     .register();                           // 得到 ChasmGui 描述，可持有用于打开
	 * }</pre>
	 *
	 * <p>打开方式：拿到 {@code register()} 返回的 {@link api.chasm.gui.ChasmGui}（或用相同的
	 * {@code Chasm.gui(modId,name)} 再调用 {@code .provider()}），在其上
	 * {@code player.openMenu(provider)} 即可在服务端弹出该界面。</p>
	 *
	 * @param modId 模组命名空间（用于 id 隔离：{@code modId:name}）
	 * @param name  界面注册名（不含命名空间）
	 */
	public static api.chasm.gui.ChasmGuiBuilder gui(String modId, String name) {
		return new api.chasm.gui.ChasmGuiBuilder(modId, name);
	}

	/**
	 * JSON 软编码物品加载器（读取 {@code data/<ns>/chasm/items/*.json}）。
	 *
	 * <p>在 onInitialize 调用 {@code Chasm.itemLoader().loadAll()} 即可自动注册所有
	 * JSON 定义的物品，无需写 Java 代码。运行时注册；DataGen 期仅收集声明（生成模型/语言）。</p>
	 */
	public static api.chasm.loader.ChasmItemLoader itemLoader() {
		return api.chasm.loader.ChasmItemLoader.INSTANCE;
	}

	/**
	 * JSON 软编码界面加载器（读取 {@code data/<ns>/chasm/gui/*.json}）。
	 *
	 * <p>起名：先在 {@code onInitialize} 注册按钮动作（{@code ChasmGuiActions.register}），
	 * 再调用 {@code Chasm.guiLoader().loadAll()} 构建并注册 JSON 界面的按钮（JSON 按钮经
	 * {@code ChasmGuiBuilder.buttonAction} 由动作 id 反查 handler）。</p>
	 */
	public static api.chasm.loader.ChasmGuiLoader guiLoader() {
		return api.chasm.loader.ChasmGuiLoader.INSTANCE;
	}

	/**
	 * 玩法模板注册表（第十四步：模板插件层）。
	 *
	 * <p>把社区验证过的玩法模式（Aquamirae 武器/食物/护甲套装）收敛为「声明 + 行为钩子 +
	 * 参数槽位」的模板：</p>
	 *
	 * <pre>{@code
	 * // 武器能力模板（命中/击杀/掉落一次声明，可被多把武器复用）
	 * WeaponTemplate GREED = Chasm.templates().weapon("mymod", "greed")
	 *     .attackDamage(5.0f).attackSpeed(1.6f)   // 传【面板总攻速】：基础 4.0 + 修饰符 -2.4 = 1.6
	 *     .onHit(ctx -> ctx.target().setSecondsOnFire(2))
	 *     .killDrop(Items.EMERALD, 3, 8, 1.0f)
	 *     .build();
	 *
	 * // 食物模板（营养/效果/余物，改改就能直接用）
	 * FoodTemplate STEW = Chasm.templates().food()
	 *     .nutrition(6).saturation(0.6f)
	 *     .remainder(Items.BOWL)
	 *     .effect(MobEffects.REGENERATION, 100, 0, 0.5f)
	 *     .build();
	 *
	 * // 护甲套装模板（材质 + 半套/全套效果）
	 * ArmorSetSpec ABYSSAL = Chasm.templates().armorSet("mymod", "abyssal")
	 *     .defense(ArmorItem.Type.HELMET, 3)
	 *     .repair(Ingredient.of(Items.IRON_INGOT))
	 *     .layer(ResourceLocation.fromNamespaceAndPath("mymod", "abyssal"))
	 *     .halfSet(ctx -> ctx.player().addEffect(...))
	 *     .fullSet(ctx -> ctx.player().addEffect(...))
	 *     .build();
	 * }</pre>
	 */
	public static api.chasm.template.ChasmTemplates templates() {
		return api.chasm.template.ChasmTemplates.INSTANCE;
	}

	/**
	 * 自定义按键注册表（第十二步）：把按键交互从左键/右键扩展到任意自定义键（如 O、L）。
	 *
	 * <p>用法（模组入口静态声明，shared 安全）：</p>
	 * <pre>{@code
	 * // 79 = GLFW O。displayName 会自动出现在原版按键控制列表（DataGen 自动生成翻译）
	 * public static final ChasmKeyBinding MAGIC_KEY =
	 *     Chasm.keys().register("mymod", "magic_blast", 79, "Magic Blast");
	 *
	 * Chasm.item()
	 *     .onKeyPress(MAGIC_KEY, ctx -> ctx.player().sendSystemMessage("按 O 发射魔法弹！"))
	 *     .register();
	 * }</pre>
	 */
	public static api.chasm.key.ChasmKeys keys() {
		return api.chasm.key.ChasmKeys.INSTANCE;
	}

	/**
	 * 方块实体类型声明（声明式 {@link BlockEntityType}，配套 {@code @Register} 注解自动注册到
	 * 原版方块实体注册表；机器方块通过 {@link api.chasm.block.BlockBuilder#blockEntity} 接线 ticker 驱动）。
	 *
	 * <p>用法（方块在前、类型在后，方块对类型的引用用惰性 Supplier 打破循环引用）：</p>
	 * <pre>{@code
	 * @Register("cooking_pot_block")
	 * public static final Block COOKING_POT_BLOCK = Chasm.block()
	 *     .strength(3.0f, 3.0f)
	 *     .name("Cooking Pot")
	 *     .texture("minecraft:block/cauldron_side")
	 *     .blockEntity(() -> COOKING_POT)          // ★ 惰性引用类型，驱动 tick
	 *     .register();
	 *
	 * @Register("cooking_pot")
	 * public static final BlockEntityType<CookingPotBE> COOKING_POT =
	 *     Chasm.blockEntityType(CookingPotBE::new) // ★ 工厂：继承 ChasmBlockEntity 的 BE 类
	 *         .blocks(COOKING_POT_BLOCK)           // ★ 该方块放置时创建本类型 BE
	 *         .register();
	 * }</pre>
	 *
	 * @param factory 方块实体工厂（{@code (BlockPos, BlockState) -> BE}，通常用方法引用）
	 * @param <T>     方块实体类型（须继承 {@link api.chasm.blockentity.ChasmBlockEntity}）
	 */
	public static <T extends api.chasm.blockentity.ChasmBlockEntity>
	api.chasm.blockentity.ChasmBlockEntityTypeBuilder<T> blockEntityType(
		net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier<T> factory) {
		return new api.chasm.blockentity.ChasmBlockEntityTypeBuilder<>(factory);
	}
}
