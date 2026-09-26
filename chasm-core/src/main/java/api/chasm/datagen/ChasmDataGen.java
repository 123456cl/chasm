package api.chasm.datagen;

import api.chasm.context.ChasmContextRegistry;
import api.chasm.context.ModContext;
import api.chasm.damage.DamageTypeInfo;
import api.chasm.enchantment.ChasmEnchantments;
import api.chasm.enchantment.EnchantmentDeclaration;
import api.chasm.item.ChasmItemController;
import api.chasm.item.ItemType;
import api.chasm.recipe.RecipeDeclaration;
import api.chasm.registry.ChasmRegistrar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.models.BlockModelGenerators;
import net.minecraft.data.models.ItemModelGenerators;
import net.minecraft.data.models.model.ModelTemplates;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Chasm 自动数据生成（DataGen）。
 *
 * <p>从所有已加载的 {@link ModContext} 自动生成资源，消灭手写 json：</p>
 * <ul>
 *   <li>物品模型（来自 {@code .texture(...)} 声明）</li>
 *   <li>语言文件（来自 {@code .name(...)} 声明）</li>
 *   <li>配方（来自 {@code Chasm.recipe(...)} 声明）</li>
 * </ul>
 *
 * <p>用法（模组的 DataGeneratorEntrypoint）：</p>
 * <pre>{@code
 * public class ExampleDataGen implements DataGeneratorEntrypoint {
 *     public void onInitializeDataGenerator(FabricDataGenerator gen) {
 *         ChasmDataGen.generate(gen, ExampleMod.class);
 *     }
 * }
 * }</pre>
 */
public class ChasmDataGen implements DataGeneratorEntrypoint {

	@Override
	public void onInitializeDataGenerator(FabricDataGenerator generator) {
		FabricDataGenerator.Pack pack = generator.createPack();
		pack.addProvider(ChasmModelProvider::new);
		pack.addProvider((output, lookup) -> new ChasmLanguageProvider(output, lookup));
		pack.addProvider((output, lookup) -> new ChasmRecipeProvider(output, lookup));
		pack.addProvider((output, lookup) -> new ChasmLootProvider(output, lookup));
		pack.addProvider((output, lookup) -> new ChasmTagProvider(output, lookup));
		// 附魔池链路：自定义附魔 JSON + 每类型独立 supported_items 物品标签
		pack.addProvider(ChasmEnchantmentProvider::new);
		pack.addProvider((output, lookup) -> new ChasmEnchantableTagProvider(output, lookup));
		// 伤害类型链路：自定义伤害类型 JSON（写入真实 data/<ns>/damage_type）+
		// 原版语义标签（bypasses_armor / is_fire / is_lightning），让声明式类型真正"落盘"
		pack.addProvider(ChasmDamageTypeProvider::new);
		pack.addProvider(ChasmDamageTypeTagProvider::new);
	}

	/** 框架自身命名空间：内置内容（如伤害类型）由使用方的 DataGen 落到使用方的数据包里。 */
	private static final String FRAMEWORK_NAMESPACE = "chasm";

	/**
	 * DataGen 生成作用域：只为本模组（含框架命名空间）生成产物。
	 *
	 * <p>为什么必须限定：同一个开发环境里可能加载了多个使用 Chasm 的模组，
	 * 而 {@link ChasmContextRegistry#all()} 是全局视图——不限定就会把别人的物品模型、
	 * 配方、语言条目生成到自己的产物目录里（互相污染，且产物里会混入第三方命名内容）。
	 * 空集合表示"不过滤"（无法确定归属时的保守回退，保持旧行为）。</p>
	 */
	private static volatile Set<String> SCOPE = Set.of();

	/** 限定 DataGen 生成作用域（供自定义生成流程使用；通常由 {@link #generate} 自动设置）。 */
	public static void scopeTo(String... modIds) {
		Set<String> ids = new LinkedHashSet<>();
		for (String id : modIds) {
			if (id != null && !id.isBlank()) {
				ids.add(id);
			}
		}
		SCOPE = ids.isEmpty() ? Set.of() : Set.copyOf(ids);
	}

	/** 当前作用域内的模组上下文（未限定时等于全局视图）。 */
	static Iterable<ModContext> scopedContexts() {
		Set<String> scope = SCOPE;
		if (scope.isEmpty()) {
			return ChasmContextRegistry.all();
		}
		List<ModContext> inScope = new ArrayList<>();
		for (ModContext ctx : ChasmContextRegistry.all()) {
			if (scope.contains(ctx.id())) {
				inScope.add(ctx);
			}
		}
		return inScope;
	}

	/** 全局 JSON 覆写注册表：数据路径（如 {@code mymod/enchantment/mana_vampire.json}）→ 覆写后的 JSON。 */
	private static final Map<String, JsonElement> OVERRIDES = new HashMap<>();

	/**
	 * <b>覆写回调（可自定义，第十二步）</b>：默认 DataGen 会为声明生成标准 JSON；
	 * 模组作者可注册一个回调，向回调传入可写 map 后把特定文件的最终 JSON 整体替换掉
	 * （例如把默认剑模型路径换成自定义模型）。
	 *
	 * <pre>{@code
	 * ChasmDataGen.setOverrides(overrides -> {
	 *     // 一键替换自定义附魔 mana_vampire 的参数（key = data 下的相对路径）
	 *     overrides.put("mymod/enchantment/mana_vampire.json",
	 *         JsonParser.parseString("{...自定义 JSON...}"));
	 * });
	 * }</pre>
	 *
	 * <p>回调应在 DataGen 期（onInitializeDataGenerator）之前调用，推荐放在模组的
	 * {@code DataGeneratorEntrypoint#onInitializeDataGenerator} 开头。</p>
	 *
	 * @param consumer 接收一个可写 map（key = 数据路径，value = 覆写后的 JSON 元素）
	 */
	public static void setOverrides(Consumer<Map<String, JsonElement>> consumer) {
		Map<String, JsonElement> map = new LinkedHashMap<>();
		consumer.accept(map);
		synchronized (OVERRIDES) {
			OVERRIDES.putAll(map);
		}
	}

	/** 直接覆写一个数据文件（key = 如 {@code mymod/enchantment/mana_vampire.json}）。 */
	public static void overrideFile(String dataPath, JsonElement json) {
		synchronized (OVERRIDES) {
			OVERRIDES.put(dataPath, json);
		}
	}

	/** 由各个手写 JSON 生成器调用：若该数据路径命中覆写，则返回覆写值，否则返回 null。 */
	static JsonElement overrideFor(String dataPath) {
		synchronized (OVERRIDES) {
			return OVERRIDES.get(dataPath);
		}
	}

	/** 引导 DataGen：先扫描指定模组类（触发静态声明），再挂载自动生成器。
	 *
	 * @param generator  数据生成器
	 * @param modClasses 待扫描的 {@code @ChasmMod} 入口类
	 */
	public static void generate(FabricDataGenerator generator, Class<?>... modClasses) {
		// 内容容器（@ChasmContentHolder）必须排在入口类之后扫：DataGen 期走的是同一条收集路径，
		// 顺序与运行时 onInitialize 保持一致，产物的收集顺序才不会漂。
		java.util.List<Class<?>> contentHolders = new java.util.ArrayList<>();
		// 本次生成归属的模组 id：扫描谁，就只为谁生成（+ 框架命名空间）。
		Set<String> owners = new LinkedHashSet<>();
		for (Class<?> modClass : modClasses) {
			if (modClass.isAnnotationPresent(api.chasm.registry.ChasmContentHolder.class)) {
				contentHolders.add(modClass);
				continue;
			}
			// DataGen 环境注册表已冻结：只收集声明（构建实例并暴露到 ModContext），不真实注册
			ChasmRegistrar.scan(modClass, false, false);
			String owner = ChasmContextRegistry.currentIdOrNull();
			if (owner != null) {
				owners.add(owner);
			}
		}
		for (Class<?> contentHolder : contentHolders) {
			ChasmRegistrar.scanContent(contentHolder, false, false);
			String owner = ChasmContextRegistry.currentIdOrNull();
			if (owner != null) {
				owners.add(owner);
			}
		}
		if (!owners.isEmpty()) {
			owners.add(FRAMEWORK_NAMESPACE);
			scopeTo(owners.toArray(new String[0]));
		}
		// 第十步：JSON 软编码物品（data/<ns>/chasm/items/*.json）在 DataGen 期也收集，
		// 使模型/语言生成器能为其生成产物（datagen 模式只暴露、不注册）。
		api.chasm.loader.ChasmItemLoader.INSTANCE.loadAll();
		new ChasmDataGen().onInitializeDataGenerator(generator);
	}

	/** 物品模型自动生成器。 */
	public static final class ChasmModelProvider extends FabricModelProvider {

		public ChasmModelProvider(FabricDataOutput output) {
			super(output);
		}

		@Override
		public void generateBlockStateModels(BlockModelGenerators blockStateModelGenerator) {
			for (ModContext ctx : scopedContexts()) {
				for (api.chasm.block.ChasmBlockController controller : ctx.blocks().values()) {
					String texture = controller.chasmTexture();
					if (texture == null) {
						continue;
					}
					// 声明贴图即引用原版方块模型：blockstate + 方块物品模型
					ResourceLocation modelId = ResourceLocation.parse(texture);
					blockStateModelGenerator.blockStateOutput.accept(
						BlockModelGenerators.createSimpleBlock(controller.block(), modelId));
					blockStateModelGenerator.delegateItemModel(controller.block(), modelId);
				}
			}
		}

		@Override
		public void generateItemModels(ItemModelGenerators itemModelGenerator) {
			for (ModContext ctx : scopedContexts()) {
				for (ChasmItemController controller : ctx.items().values()) {
					// 自带模型的物品跳过：自动生成的占位模型会和自带模型同路径，导致资源重复而构建失败
					if (!controller.chasmAutoModel()) {
						continue;
					}
					String texture = controller.chasmTexture();
					if (texture == null) {
						continue;
					}
					// 约定：**贴图路径的最后一段 = 一个已注册物品的 id**（例如 "apoth:item/gem" ↔ 物品 apoth:gem）。
					//
					// 踩过的坑（2026-09 实测）：不满足这个约定时会静默退化成 minecraft:item/air ——
					// 模型照生成、进游戏是空图标，日志里只有一行 Missing textures，极难排查。
					// 所以这里**显式告警**，别再让它静默发生。
					Item textureItem = BuiltInRegistries.ITEM.get(
						ResourceLocation.parse(texture.replace("item/", "")));
					if (textureItem == net.minecraft.world.item.Items.AIR) {
						api.chasm.log.ChasmLogger.warn(
							BuiltInRegistries.ITEM.getKey(controller.item()).getNamespace(),
							"物品 {} 的贴图声明 {} 找不到同名物品 → 模型会退化成空气图标。"
								+ "请把贴图命名为 <物品 id>.png 并在 .texture() 里写成 \"<ns>:item/<物品 id>\"",
							BuiltInRegistries.ITEM.getKey(controller.item()), texture);
						continue;
					}
					// 工具类物品用手持模型，其余用平面模型（同一物品只生成一个模型，避免冲突）
					if (isToolLike(texture)) {
						itemModelGenerator.generateFlatItem(controller.item(), textureItem,
							ModelTemplates.FLAT_HANDHELD_ITEM);
					} else {
						itemModelGenerator.generateFlatItem(controller.item(), textureItem, ModelTemplates.FLAT_ITEM);
					}
				}
			}
		}

		private static boolean isToolLike(String texture) {
			return texture.contains("sword") || texture.contains("pickaxe")
				|| texture.contains("axe") || texture.contains("shovel") || texture.contains("hoe");
		}
	}

	/** 语言文件自动生成器（en_us）。 */
	public static final class ChasmLanguageProvider extends FabricLanguageProvider {

		public ChasmLanguageProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registryLookup) {
			super(output, registryLookup);
		}

		@Override
		public void generateTranslations(HolderLookup.Provider registryLookup, TranslationBuilder translationBuilder) {
			for (ModContext ctx : scopedContexts()) {
				for (ChasmItemController controller : ctx.items().values()) {
					String name = controller.chasmDisplayName();
					if (name != null) {
						translationBuilder.add(controller.item(), name);
					}
				}
				for (api.chasm.block.ChasmBlockController controller : ctx.blocks().values()) {
					String name = controller.chasmDisplayName();
					if (name != null) {
						translationBuilder.add(controller.block(), name);
					}
				}
			}
			// 第十二步：按键可见名自动生成（分类名 + 每个声明按键的显示名）。
			// 让 Chasm.keys().register(...) 声明的按键开箱即出现在原版按键控制列表且可读。
			translationBuilder.add("key.category.chasm", "Chasm Controls");
			for (api.chasm.item.ChasmKeyBinding key : api.chasm.key.ChasmKeys.INSTANCE.all()) {
				translationBuilder.add(key.translationKey(), key.displayName());
			}
			// 修复附魔翻译键缺失：为每个自定义附魔生成 名称 + 描述 lang 条目。
			// 附魔 JSON 的 description 引用 enchantment.<ns>.<path>（见 EnchantmentDeclaration#toJson），
			// 若无 lang 条目游戏内会显示原始翻译键而非名称；此处补齐，开箱即可读。
			for (EnchantmentDeclaration decl : ChasmEnchantments.INSTANCE.allDeclarations()) {
				translationBuilder.add(decl.translationKey(), decl.displayName());
				String desc = decl.displayDescription();
				if (desc != null && !desc.isEmpty()) {
					translationBuilder.add(decl.descTranslationKey(), desc);
				}
			}
		}
	}

	/** 配方自动生成器。 */
	public static final class ChasmRecipeProvider extends FabricRecipeProvider {

		public ChasmRecipeProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registryLookup) {
			super(output, registryLookup);
		}

		@Override
		public void buildRecipes(RecipeOutput exporter) {
			for (ModContext ctx : scopedContexts()) {
				String modId = ctx.id();
				for (RecipeDeclaration recipe : ctx.recipes().values()) {
					ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(RecipeCategory.COMBAT,
						recipe.result().getItem(), recipe.result().getCount());
					for (String row : recipe.pattern()) {
						builder.pattern(row);
					}
					recipe.keys().forEach(builder::define);
					Item unlockItem = firstItem(recipe);
					builder.unlockedBy("has_" + unlockItem.getDescriptionId().replaceAll("[^a-z0-9_]", "_"),
						has(unlockItem));
					ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath(modId, recipe.id());
					builder.save(exporter, recipeId);
				}
			}
		}

		private static Item firstItem(RecipeDeclaration recipe) {
			for (Ingredient ingredient : recipe.keys().values()) {
				var items = ingredient.getItems();
				if (items.length > 0) {
					return items[0].getItem();
				}
			}
			return recipe.result().getItem();
		}
	}

	/** 方块掉落自动生成器（按 {@code drops/dropsRandom/loot} 声明生成 loot table，支持多工具+概率）。 */
	public static final class ChasmLootProvider extends net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootTableProvider {

		public ChasmLootProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registryLookup) {
			super(output, registryLookup);
		}

		@Override
		public void generate() {
			for (ModContext ctx : scopedContexts()) {
				for (api.chasm.block.ChasmBlockController controller : ctx.blocks().values()) {
					api.chasm.block.LootDeclaration loot = controller.chasmLoot();
					if (loot == null) {
						// 该方块**自带掉落表**（assets 侧的 data/<ns>/loot_table/blocks/<id>.json，由模组自己提供）：
						// 生成同路径文件会让 processResources 报 duplicate（§49 铁律），所以跳过。
						continue;
					}
					generateBlockLoot(controller.block(), loot);
				}
			}
		}

		@SuppressWarnings("unchecked")
		private void generateBlockLoot(net.minecraft.world.level.block.Block block,
			api.chasm.block.LootDeclaration loot) {
			var pool = net.minecraft.world.level.storage.loot.LootPool.lootPool()
				.setRolls(net.minecraft.world.level.storage.loot.providers.number.ConstantValue.exactly(1));
			for (api.chasm.block.LootDeclaration.LootRule rule : loot.rules()) {
				if (rule.type() == api.chasm.block.LootDeclaration.LootType.NOTHING) {
					continue; // 无掉落：不添加条目（该工具条件挖不出东西）
				}
				net.minecraft.world.level.ItemLike item = rule.type() == api.chasm.block.LootDeclaration.LootType.SELF
					? block : rule.item();
				net.minecraft.world.level.storage.loot.entries.LootItem.Builder<?> entry =
					net.minecraft.world.level.storage.loot.entries.LootItem.lootTableItem(item);
				// 数量：固定或随机
				if (rule.type() == api.chasm.block.LootDeclaration.LootType.ITEM) {
					entry = (net.minecraft.world.level.storage.loot.entries.LootItem.Builder<?>) entry.apply(
						net.minecraft.world.level.storage.loot.functions.SetItemCountFunction.setCount(
							net.minecraft.world.level.storage.loot.providers.number.ConstantValue.exactly(rule.count())));
				} else if (rule.type() == api.chasm.block.LootDeclaration.LootType.ITEM_RANDOM) {
					entry = (net.minecraft.world.level.storage.loot.entries.LootItem.Builder<?>) entry.apply(
						net.minecraft.world.level.storage.loot.functions.SetItemCountFunction.setCount(
							net.minecraft.world.level.storage.loot.providers.number.UniformGenerator.between(rule.min(), rule.max())));
				}

				java.util.List<net.minecraft.world.level.storage.loot.predicates.LootItemCondition.Builder> conditions =
					new java.util.ArrayList<>();
				// 工具物品条件
				if (rule.toolItemId() != null) {
					conditions.add(net.minecraft.world.level.storage.loot.predicates.MatchTool.toolMatches(
						net.minecraft.advancements.critereon.ItemPredicate.Builder.item()
							.of(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
								ResourceLocation.parse(rule.toolItemId())))));
				}
				// 工具标签条件
				if (rule.toolTagId() != null) {
					conditions.add(net.minecraft.world.level.storage.loot.predicates.MatchTool.toolMatches(
						net.minecraft.advancements.critereon.ItemPredicate.Builder.item()
							.of(net.minecraft.tags.TagKey.create(
								net.minecraft.core.registries.Registries.ITEM,
								ResourceLocation.parse(rule.toolTagId())))));
				}
				// 概率条件
				if (rule.chance() < 1f) {
					conditions.add(net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition
						.randomChance(rule.chance()));
				}

				if (!conditions.isEmpty()) {
					entry = (net.minecraft.world.level.storage.loot.entries.LootItem.Builder<?>)
						entry.when(conditions, b -> b);
				}
				pool.add(entry);
			}
			add(block, net.minecraft.world.level.storage.loot.LootTable.lootTable().withPool(pool));
		}
	}

	/** 方块挖掘标签自动生成器（来自 {@code requiresPickaxe/requiresTool} 等声明，支持任意自定义标签）。 */
	public static final class ChasmTagProvider extends net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider<net.minecraft.world.level.block.Block> {

		public ChasmTagProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registryLookup) {
			super(output, net.minecraft.core.registries.Registries.BLOCK, registryLookup);
		}

		@Override
		protected void addTags(HolderLookup.Provider wrapperLookup) {
			for (ModContext ctx : scopedContexts()) {
				for (api.chasm.block.ChasmBlockController controller : ctx.blocks().values()) {
					String tool = controller.chasmRequiredTool();
					if (tool == null) {
						continue;
					}
					// 支持任意标签路径（如 minecraft:mineable/pickaxe 或自定义 mymod:mineable/magic）
					ResourceLocation tagId = ResourceLocation.parse(tool);
					getOrCreateTagBuilder(net.minecraft.tags.TagKey.create(
						net.minecraft.core.registries.Registries.BLOCK, tagId)).add(controller.block());
				}
			}
		}
	}

	/**
	 * 自定义附魔自动生成器（data-driven 核心）。
	 *
	 * <p>把 {@link ChasmEnchantments} 声明的每个附魔输出为
	 * {@code data/<ns>/enchantment/<path>.json}（1.21.1 数据驱动动态注册表格式）。
	 * 没有这些 JSON，自定义附魔根本无法被加载。附魔字段由
	 * {@link EnchantmentDeclaration#toJson()} 生成，与 {@code Enchantment.DIRECT_CODEC}
	 * 一致。1.21.1 附魔无独立 treasure 字段，宝藏语义由附魔池标签的聚合粒度承担。</p>
	 */
	public static final class ChasmEnchantmentProvider implements DataProvider {

		private final FabricDataOutput output;

		public ChasmEnchantmentProvider(FabricDataOutput output) {
			this.output = output;
		}

		@Override
		public CompletableFuture<?> run(CachedOutput cache) {
			List<CompletableFuture<?>> futures = new ArrayList<>();
			// DATA_PACK 目标即生成器输出根下的 "data" 目录，各模组以命名空间组织
			Path dataRoot = output.getOutputFolder(net.minecraft.data.PackOutput.Target.DATA_PACK);
			for (EnchantmentDeclaration decl : ChasmEnchantments.INSTANCE.allDeclarations()) {
				ResourceLocation id = decl.location();
				JsonElement json = decl.toJson();
				Path target = dataRoot.resolve(id.getNamespace())
					.resolve("enchantment").resolve(id.getPath() + ".json");
				// 第十二步：支持覆写回调整体替换生成的附魔 JSON（默认可生成 + 可自定义）
				JsonElement override = overrideFor(
					id.getNamespace() + "/enchantment/" + id.getPath() + ".json");
				if (override != null) {
					json = override;
				}
				futures.add(DataProvider.saveStable(cache, json, target));
			}
			return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
		}

		@Override
	public String getName() {
		return "Chasm Enchantments";
	}
}

	/**
	 * 每类型独立 supported_items 物品标签自动生成器。
	 *
	 * <p>附魔 JSON 的 {@code supported_items} 指向 {@code <ns>:enchantable/<path>}
	 * 标签（{@link EnchantmentDeclaration#supportedTag()}）；本生成器把「绑定到某类型附魔池
	 * 该附魔的所有物品」聚合进该标签。据此，原版附魔台天然会为这类物品刷出专属附魔（含自定义
	 * 附魔），无需任何 Mixin —— 这是 1.21.1 数据驱动设计推荐的做法。</p>
	 *
	 * <p>{@code treasureOnly} 的池成员不写入标签：它们不会出现在附魔台候选，仅在
	 * {@code ItemBuilder.enchant()} 自带或战利品经数据直接注入时生效（"仅战利品获得"）。</p>
	 */
	public static final class ChasmEnchantableTagProvider extends FabricTagProvider<Item> {

		public ChasmEnchantableTagProvider(FabricDataOutput output,
			CompletableFuture<HolderLookup.Provider> registryLookup) {
			super(output, Registries.ITEM, registryLookup);
		}

		@Override
		protected void addTags(HolderLookup.Provider wrapperLookup) {
			// 按附魔的 supported_items 标签聚合其归属物品（跳过 treasureOnly 池成员）。
			// 用 LinkedHashSet 聚合：同一物品若因属于多个 item 类型或其类型附魔池有多个成员，
			// 可能被重复 add 到同一标签。Set 在生成前先去重，且保持首次出现顺序，避免生成重复条目。
			Map<TagKey<Item>, Set<Item>> byTag = new HashMap<>();
			for (ModContext ctx : scopedContexts()) {
				for (ChasmItemController controller : ctx.items().values()) {
					ItemType type = controller.chasmType();
					if (type == null) {
						continue;
					}
					Item item = controller.item();
					for (api.chasm.enchantment.EnchantmentConfig config : type.enchantmentPool().values()) {
						if (config.treasureOnly()) {
							continue; // 宝藏附魔：不进附魔台候选，仅自带/战利品注入
						}
						byTag.computeIfAbsent(config.supportedTag(), k -> new LinkedHashSet<>()).add(item);
					}
				}
			}
			byTag.forEach((tag, items) -> {
				var appender = getOrCreateTagBuilder(tag);
				for (Item item : items) {
					appender.add(item);
				}
			});
		}
	}

	/**
	 * 自定义伤害类型自动生成器（修复"只写内存投影"）。
	 *
	 * <p>把 {@link ChasmDamageTypes} 声明的每个类型输出为
	 * {@code data/<ns>/damage_type/<path>.json}（1.21.1 数据驱动格式）。
	 * 之前这些类型只存在于 {@code ChasmDamageTypes} 内存表、从未落盘，导致自定义死亡消息/
	 * 缩放/标签语义在游戏中完全无法生效；本生成器让声明式类型成为真实可用的 DamageType。</p>
	 *
	 * <p>1.21.1 的 {@code DamageType} 为扁平 record（message_id/exhaustion/scaling/effects/
	 * death_message_type）；bypassArmor/isFire 等语义在 1.21 起改由 damage_type 标签承担，
	 * 由 {@link ChasmDamageTypeTagProvider} 生成。</p>
	 */
	public static final class ChasmDamageTypeProvider implements DataProvider {

		private final FabricDataOutput output;

		public ChasmDamageTypeProvider(FabricDataOutput output) {
			this.output = output;
		}

		@Override
		public CompletableFuture<?> run(CachedOutput cache) {
			List<CompletableFuture<?>> futures = new ArrayList<>();
			Path dataRoot = output.getOutputFolder(net.minecraft.data.PackOutput.Target.DATA_PACK);
			for (DamageTypeInfo info : api.chasm.damage.ChasmDamageTypes.INSTANCE.all()) {
				ResourceLocation id = info.id();
				JsonElement json = toJson(info);
				Path target = dataRoot.resolve(id.getNamespace())
					.resolve("damage_type").resolve(id.getPath() + ".json");
				// 第十二步：支持覆写回调整体替换生成的伤害类型 JSON（默认可生成 + 可自定义）
				JsonElement override = overrideFor(
					id.getNamespace() + "/damage_type/" + id.getPath() + ".json");
				if (override != null) {
					json = override;
				}
				futures.add(DataProvider.saveStable(cache, json, target));
			}
			return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
		}

		/** 把 {@link DamageTypeInfo} 转为 1.21.1 的 DamageType JSON（扁平 record 字段）。 */
		private static JsonElement toJson(DamageTypeInfo info) {
			JsonObject obj = new JsonObject();
			obj.addProperty("message_id", info.messageId());
			obj.addProperty("exhaustion", info.exhaustion());
			// scaling：火焰类型永不缩放；其余按 scalesWithDifficulty 映射
			obj.addProperty("scaling", info.isFire()
				? "never"
				: (info.scalesWithDifficulty() ? "always" : "when_caused_by_living_non_player"));
			obj.addProperty("effects", info.isFire() ? "burning" : "hurt");
			obj.addProperty("death_message_type", "default");
			return obj;
		}

		@Override
		public String getName() {
			return "Chasm Damage Types";
		}
	}

	/**
	 * 伤害类型语义标签生成器（1.21 起 bypassArmor/isFire 由标签承担）。
	 *
	 * <p>把声明时标记了 {@code bypassesArmor()}/{@code isFire()} 或
	 * {@code LIGHTNING} 类别的类型追加进原版对应语义标签（{@code minecraft:bypasses_armor}、
	 * {@code minecraft:is_fire}、{@code minecraft:is_lightning}），从而被原版护甲/引燃/雷电
	 * 机制真正识别 —— 这是"声明式伤害类型语义落盘"的最后一环。</p>
	 *
	 * <p><b>实现说明（修复 datagen 校验失败）</b>：自定义伤害类型在 datagen 期只写入
	 * {@code data/<ns>/damage_type/*.json}，并未进入运行时动态注册表；若用
	 * {@code FabricTagProvider} 的 {@code add(ResourceKey)} 引用它们，会在标签生成时
	 * 对 {@code wrapperLookup} 校验引用而报 "missing following references" 崩溃。
	 * 因此本生成器改为<b>手写合并的原始标签 JSON</b>（{@code replace:false} 与原版数据包
	 * 合并，运行时自定义类型已从 JSON 落盘注册、引用必然存在），跨模组同标签自动聚合一次写入。</p>
	 */
	public static final class ChasmDamageTypeTagProvider implements DataProvider {

		private final FabricDataOutput output;

		public ChasmDamageTypeTagProvider(FabricDataOutput output) {
			this.output = output;
		}

		@Override
		public CompletableFuture<?> run(CachedOutput cache) {
			// 语义标签（原版命名空间）→ 命中该语义的自定义伤害类型 id 集合（保持声明顺序）
			Map<String, Set<String>> tags = new LinkedHashMap<>();
			for (DamageTypeInfo info : api.chasm.damage.ChasmDamageTypes.INSTANCE.all()) {
				String id = info.id().toString();
				if (info.bypassesArmor()) {
					tags.computeIfAbsent("bypasses_armor", k -> new LinkedHashSet<>()).add(id);
				}
				if (info.isFire()) {
					tags.computeIfAbsent("is_fire", k -> new LinkedHashSet<>()).add(id);
				}
				if (info.kind() == api.chasm.damage.DamageTypeKind.LIGHTNING) {
					tags.computeIfAbsent("is_lightning", k -> new LinkedHashSet<>()).add(id);
				}
			}
			Path dataRoot = output.getOutputFolder(net.minecraft.data.PackOutput.Target.DATA_PACK);
			List<CompletableFuture<?>> futures = new ArrayList<>();
			for (Map.Entry<String, Set<String>> entry : tags.entrySet()) {
				JsonObject json = new JsonObject();
				json.addProperty("replace", false); // 与原版数据包的既有条目合并
				com.google.gson.JsonArray values = new com.google.gson.JsonArray();
				for (String id : entry.getValue()) {
					values.add(id);
				}
				json.add("values", values);
				Path target = dataRoot.resolve("minecraft/tags/damage_type")
					.resolve(entry.getKey() + ".json");
				JsonElement override = overrideFor("minecraft/tags/damage_type/" + entry.getKey() + ".json");
				JsonElement toSave = override != null ? override : json;
				futures.add(DataProvider.saveStable(cache, toSave, target));
			}
			return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
		}

		@Override
		public String getName() {
			return "Chasm Damage Type Tags";
		}
	}
}