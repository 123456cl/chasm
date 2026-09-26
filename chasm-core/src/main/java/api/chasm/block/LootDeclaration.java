package api.chasm.block;

import net.minecraft.world.level.ItemLike;

import java.util.List;

/**
 * 方块掉落声明（DataGen 生成 loot table 的数据源）。
 *
 * <p>支持多规则：每种工具（物品或标签）可配置独立掉落，可带概率。</p>
 *
 * <pre>{@code
 * .loot(l -> l
 *     .onTool(Items.DIAMOND_PICKAXE, r -> r.dropsSelf())
 *     .onTool(Items.DIAMOND_SWORD, r -> r.dropsRandom(Items.AMETHYST_SHARD, 1, 3))
 *     .onTool(Items.DIAMOND_HOE, r -> r.chance(0.5f).dropsSelf())
 *     .otherwise(r -> r.drops(Items.AMETHYST_SHARD)))
 * }</pre>
 */
public record LootDeclaration(List<LootRule> rules) {

	public enum LootType {
		/** 掉落方块自身 */
		SELF,
		/** 掉落指定物品固定数量 */
		ITEM,
		/** 掉落指定物品随机数量 */
		ITEM_RANDOM,
		/** 不掉落 */
		NOTHING
	}

	/**
	 * 单条掉落规则。
	 *
	 * @param type       掉落类型
	 * @param item       掉落物品（SELF/NOTHING 时忽略）
	 * @param count      固定数量（ITEM）
	 * @param min        随机最小数量（ITEM_RANDOM）
	 * @param max        随机最大数量（ITEM_RANDOM）
	 * @param toolItemId 工具物品 id（如 "minecraft:diamond_sword"，null = 不限制工具）
	 * @param toolTagId  工具标签（如 "mymod:special_tools"，null = 不限制）
	 * @param chance     触发概率（0~1，1 = 必定）
	 */
	public record LootRule(LootType type, ItemLike item, int count, int min, int max,
		String toolItemId, String toolTagId, float chance) {
	}

	/** 默认：掉落方块自身。 */
	public static final LootDeclaration DEFAULT = new LootDeclaration(List.of(
		new LootRule(LootType.SELF, null, 0, 0, 0, null, null, 1f)));

	/** 单条规则（快捷构造）。 */
	public static LootDeclaration single(LootType type, ItemLike item, int count, int min, int max) {
		return new LootDeclaration(List.of(
			new LootRule(type, item, count, min, max, null, null, 1f)));
	}
}
