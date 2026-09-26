package api.chasm.block;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 多工具掉落声明 Builder。
 *
 * <p>按工具（物品/标签）配置各自的掉落结果，可带概率：</p>
 *
 * <pre>{@code
 * .loot(l -> l
 *     .onTool(Items.DIAMOND_PICKAXE, r -> r.dropsSelf())
 *     .onTool(Items.DIAMOND_SWORD, r -> r.dropsRandom(Items.AMETHYST_SHARD, 1, 3))
 *     .onTool(Items.DIAMOND_HOE, r -> r.chance(0.5f).dropsSelf())
 *     .onToolTag("mymod:special_tools", r -> r.drops(OtherModItem.SOMETHING))
 *     .otherwise(r -> r.drops(Items.AMETHYST_SHARD, 2)))
 * }</pre>
 */
public final class LootBuilder {

	private final List<LootDeclaration.LootRule> rules = new ArrayList<>();

	LootBuilder() {
	}

	/** 指定工具物品的掉落（如 {@code Items.DIAMOND_SWORD}，支持其他模组物品）。 */
	public LootBuilder onTool(ItemLike tool, Consumer<LootResult> result) {
		return addRule(toolId(tool), null, result);
	}

	/** 指定工具标签的掉落（如 "minecraft:mineable/pickaxe" 或自定义标签）。 */
	public LootBuilder onToolTag(String tagId, Consumer<LootResult> result) {
		return addRule(null, tagId, result);
	}

	/** 其余情况（任何工具）的默认掉落。 */
	public LootBuilder otherwise(Consumer<LootResult> result) {
		return addRule(null, null, result);
	}

	private LootBuilder addRule(String toolItemId, String toolTagId, Consumer<LootResult> result) {
		LootResult r = new LootResult();
		result.accept(r);
		rules.add(new LootDeclaration.LootRule(r.type, r.item, r.count, r.min, r.max,
			toolItemId, toolTagId, r.chance));
		return this;
	}

	List<LootDeclaration.LootRule> build() {
		return List.copyOf(rules);
	}

	private static String toolId(ItemLike tool) {
		return BuiltInRegistries.ITEM.getKey(tool.asItem()).toString();
	}

	/** 单条掉落结果定义器。 */
	public static final class LootResult {

		private LootDeclaration.LootType type = LootDeclaration.LootType.ITEM;
		private ItemLike item;
		private int count = 1;
		private int min;
		private int max;
		private float chance = 1f;

		LootResult() {
		}

		/** 掉落方块自身。 */
		public LootResult dropsSelf() {
			this.type = LootDeclaration.LootType.SELF;
			return this;
		}

		/** 掉落指定物品固定数量（支持其他模组物品）。 */
		public LootResult drops(ItemLike item, int count) {
			this.type = LootDeclaration.LootType.ITEM;
			this.item = item;
			this.count = count;
			return this;
		}

		/** 掉落指定物品 1 个。 */
		public LootResult drops(ItemLike item) {
			return drops(item, 1);
		}

		/** 掉落指定物品随机数量。 */
		public LootResult dropsRandom(ItemLike item, int min, int max) {
			this.type = LootDeclaration.LootType.ITEM_RANDOM;
			this.item = item;
			this.min = min;
			this.max = max;
			return this;
		}

		/** 不掉落。 */
		public LootResult nothing() {
			this.type = LootDeclaration.LootType.NOTHING;
			return this;
		}

		/** 触发概率（0~1，1 = 必定；配合掉落结果使用，如 {@code chance(0.5f).dropsSelf()}）。 */
		public LootResult chance(float chance) {
			this.chance = chance;
			return this;
		}
	}
}
