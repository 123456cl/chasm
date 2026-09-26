package api.chasm.loot.rarity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * **战利品稀有度**（神化 {@code LootRarity}）：颜色 + 排序 + 权重 + 一串生成规则。
 *
 * <p>三个语义被统一在一个对象里（神化也是这么做的）：</p>
 * <ul>
 *   <li><b>生成</b>：{@link #roll(RandomSource)} 走一遍规则 → {@link RarityBuild}；</li>
 *   <li><b>展示</b>：{@link #color} 决定物品名与提示行的颜色，{@link #sortIndex} 决定排序；</li>
 *   <li><b>抽取</b>：{@link #weight} 决定世界自然掉落里各稀有度的相对概率。</li>
 * </ul>
 *
 * <p>与前作的区别：稀有度**不再自己知道怎么造装备**，只产出计划；具体装备怎么写由调用方决定。
 * 这样同一套稀有度可以同时驱动"重铸台重铸"、"宝箱自然生成"、"Boss 掉落"三条路径。</p>
 */
public final class LootRarity {

	private final ResourceLocation id;
	private final String displayName;
	private final int color;
	private final int sortIndex;
	private final double weight;
	private final List<RarityRule> rules;

	private LootRarity(ResourceLocation id, String displayName, int color, int sortIndex, double weight,
		List<RarityRule> rules) {
		if (id == null) {
			throw new IllegalArgumentException("稀有度 id 不可为 null");
		}
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("稀有度 " + id + " 缺少显示名");
		}
		if (weight < 0) {
			throw new IllegalArgumentException("稀有度 " + id + " 权重不可为负: " + weight);
		}
		this.id = id;
		this.displayName = displayName;
		this.color = color;
		this.sortIndex = sortIndex;
		this.weight = weight;
		this.rules = List.copyOf(rules);
	}

	public static Builder builder(String modId, String path) {
		return new Builder(ResourceLocation.fromNamespaceAndPath(modId, path));
	}

	public static Builder builder(ResourceLocation id) {
		return new Builder(id);
	}

	public ResourceLocation id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	/** ARGB 颜色（名字/提示行）。 */
	public int color() {
		return color;
	}

	/** RGB 分量（拼 Style 用）。 */
	public int rgb() {
		return color & 0xFFFFFF;
	}

	/** 排序值（神化的 {@code sort_index}，越大越高级）。 */
	public int sortIndex() {
		return sortIndex;
	}

	public double weight() {
		return weight;
	}

	public List<RarityRule> rules() {
		return rules;
	}

	/** 走一遍规则，产出这件装备的生成计划。 */
	public RarityBuild roll(RandomSource rand) {
		RarityBuild build = new RarityBuild();
		for (RarityRule rule : rules) {
			rule.apply(build, rand);
		}
		return build;
	}

	@Override
	public String toString() {
		return "LootRarity[" + id + "]";
	}

	/** 构建器。 */
	public static final class Builder {
		private final ResourceLocation id;
		private String displayName = "稀有度";
		private int color = 0xFFFFFFFF;
		private int sortIndex;
		private double weight = 1.0;
		private final List<RarityRule> rules = new java.util.ArrayList<>();

		private Builder(ResourceLocation id) {
			this.id = id;
		}

		public Builder name(String displayName) {
			this.displayName = displayName;
			return this;
		}

		public Builder color(int argb) {
			this.color = argb;
			return this;
		}

		public Builder sortIndex(int sortIndex) {
			this.sortIndex = sortIndex;
			return this;
		}

		public Builder weight(double weight) {
			this.weight = weight;
			return this;
		}

		public Builder rule(RarityRule rule) {
			this.rules.add(rule);
			return this;
		}

		public Builder affix(String affixType, int count) {
			return rule(new RarityRule.Affix(affixType, count));
		}

		public Builder chanced(float chance, RarityRule rule) {
			return rule(new RarityRule.Chanced(chance, rule));
		}

		public Builder sockets(int min, int max) {
			return rule(new RarityRule.Socket(min, max));
		}

		public Builder durability(float min, float max) {
			return rule(new RarityRule.Durability(min, max));
		}

		public LootRarity build() {
			if (rules.isEmpty()) {
				throw new IllegalStateException("稀有度 " + id + " 没有任何规则");
			}
			return new LootRarity(id, displayName, color, sortIndex, weight, rules);
		}
	}
}
