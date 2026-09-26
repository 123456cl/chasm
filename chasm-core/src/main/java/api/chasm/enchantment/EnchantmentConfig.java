package api.chasm.enchantment;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * 附魔池配置：描述"某物品类型可用某个附魔"的规则表项。
 *
 * <p>与物品类型（{@link api.chasm.item.ItemType}）绑定，决定该类型的物品在附魔台上能刷出
 * 哪些专属附魔（含 {@link ChasmEnchantments} 注册的自定义附魔），以及附魔的等级范围、出现权重。</p>
 *
 * @param minLevel    该附魔可出现的等级下限（附魔台候选至少以此等级显示）
 * @param maxLevel    该附魔可出现的等级上限（通常取注册的最大等级）
 * @param weight      出现权重（越大越容易刷出；用于与同池其他附魔的加权比较）
 * @param treasureOnly 是否仅战利品获得（true 时不进入附魔台候选，仅经附魔书/战利品注入）
 */
public record EnchantmentConfig(
	ResourceKey<Enchantment> enchantment,
	int minLevel,
	int maxLevel,
	int weight,
	boolean treasureOnly) {

	/** 便捷构造：非宝藏（可经附魔台获得）。 */
	public EnchantmentConfig(ResourceKey<Enchantment> enchantment, int minLevel, int maxLevel, int weight) {
		this(enchantment, minLevel, maxLevel, weight, false);
	}

	/**
	 * 从声明直接构造配置（便捷重载，供 {@code ItemType.Builder.enchantment(EnchantmentDeclaration,...)}
	 * 内部复用注册的最大等级）。
	 */
	public static EnchantmentConfig fromDeclaration(EnchantmentDeclaration decl, int minLevel, int maxLevel,
		int weight, boolean treasureOnly) {
		return new EnchantmentConfig(decl.key(), minLevel, maxLevel, weight, treasureOnly);
	}

	/** 转为附魔注册键（等价于 {@link #enchantment()}）。 */
	public ResourceKey<Enchantment> key() {
		return enchantment;
	}

	/** 便捷：本配置引用的附魔 tag（默认所在注册表）。 */
	public net.minecraft.tags.TagKey<Item> supportedTag() {
		return ChasmEnchantments.supportedItemsTag(enchantment);
	}
}