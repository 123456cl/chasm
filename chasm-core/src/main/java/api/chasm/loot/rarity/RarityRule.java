package api.chasm.loot.rarity;

import net.minecraft.util.RandomSource;

/**
 * **稀有度规则**（神化 {@code LootRule} 的开放版）：一条规则决定"这件词缀装备长什么样"。
 *
 * <p>神化的稀有度 JSON 是一串规则：</p>
 * <pre>{@code
 * "rules": [
 *   {"type":"apotheosis:affix","affix_type":"stat"},
 *   {"type":"apotheosis:chanced","chance":0.25,"rule":{"type":"apotheosis:affix","affix_type":"stat"}},
 *   {"type":"apotheosis:socket","min":1,"max":3},
 *   {"type":"apotheosis:durability","min":0.45,"max":0.75}
 * ]
 * }</pre>
 *
 * <p>本接口把这套语义变成**可注册协议**：任何模组都能加自己的规则类型（"必带某词缀"、"诅咒几率"、
 * "镶嵌上限 +1"……），引擎不改一行。规则只往 {@link RarityBuild} 里写"计划"，具体数值在构建时掷。</p>
 */
public interface RarityRule {

	/** 规则类型键（数据包 JSON 的 {@code type}；也用于注册表查表）。 */
	String type();

	/** 施加规则（随机源是本次掷点的唯一随机性来源 → 同种子可复现）。 */
	void apply(RarityBuild build, RandomSource rand);

	// ---------------------------------------------------------------- 内置规则（语义抄神化）

	/** 加 N 条某类词缀（{@code affix_type} 如 stat / basic_effect / ability）。 */
	record Affix(String affixType, int count) implements RarityRule {
		public Affix {
			if (affixType == null || affixType.isBlank()) {
				throw new IllegalArgumentException("词缀类型不可为空");
			}
			if (count < 0) {
				throw new IllegalArgumentException("词缀条数不可为负: " + count);
			}
		}

		@Override
		public String type() {
			return "affix";
		}

		@Override
		public void apply(RarityBuild build, RandomSource rand) {
			build.addAffix(affixType, count);
		}
	}

	/** 有 {@code chance} 概率施加子规则（神化 {@code apotheosis:chanced}）。 */
	record Chanced(float chance, RarityRule rule) implements RarityRule {
		public Chanced {
			if (rule == null) {
				throw new IllegalArgumentException("子规则不可为空");
			}
		}

		@Override
		public String type() {
			return "chanced";
		}

		@Override
		public void apply(RarityBuild build, RandomSource rand) {
			if (rand.nextFloat() < chance) {
				rule.apply(build, rand);
			}
		}
	}

	/** 二选一（神化 {@code apotheosis:select}：{@code if_true} / {@code if_false}）。 */
	record Select(float chance, RarityRule ifTrue, RarityRule ifFalse) implements RarityRule {
		public Select {
			if (ifTrue == null || ifFalse == null) {
				throw new IllegalArgumentException("两支规则都不可为空");
			}
		}

		@Override
		public String type() {
			return "select";
		}

		@Override
		public void apply(RarityBuild build, RandomSource rand) {
			(rand.nextFloat() < chance ? ifTrue : ifFalse).apply(build, rand);
		}
	}

	/** 镶孔数区间（神化 {@code apotheosis:socket}）。 */
	record Socket(int min, int max) implements RarityRule {
		public Socket {
			if (min < 0 || max < min) {
				throw new IllegalArgumentException("镶孔区间非法: " + min + ".." + max);
			}
		}

		@Override
		public String type() {
			return "socket";
		}

		@Override
		public void apply(RarityBuild build, RandomSource rand) {
			build.setSocketRange(min, max);
		}
	}

	/** 耐久加成区间（神化 {@code apotheosis:durability}：无视 N% 耐久损耗）。 */
	record Durability(float min, float max) implements RarityRule {
		public Durability {
			if (min < 0 || max < min) {
				throw new IllegalArgumentException("耐久加成区间非法: " + min + ".." + max);
			}
		}

		@Override
		public String type() {
			return "durability";
		}

		@Override
		public void apply(RarityBuild build, RandomSource rand) {
			build.setDurabilityRange(min, max);
		}
	}

	/** 不可损坏（神化 {@code component: {minecraft:unbreakable}}）。 */
	record Unbreakable() implements RarityRule {
		@Override
		public String type() {
			return "unbreakable";
		}

		@Override
		public void apply(RarityBuild build, RandomSource rand) {
			build.setUnbreakable(true);
		}
	}
}
