package api.chasm.loot.rarity;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **稀有度掷点结果**：规则往这里写"计划"，装备生成器再按计划造装备。
 *
 * <p>为什么中间要有它：神化的规则是**直接改物品**的（规则里就能 setComponent），
 * 结果是"稀有度"和"物品"耦合死。这里拆开——稀有度只产出计划（几条什么类型的词缀、
 * 镶孔几到几、耐久加成多少、是否不可损坏），换一套装备系统也能复用同一套稀有度。</p>
 */
public final class RarityBuild {

	/** 词缀类型 → 条数（保持规则的书写顺序）。 */
	private final Map<String, Integer> affixes = new LinkedHashMap<>();
	private int socketMin;
	private int socketMax;
	private boolean socketsSet;
	private float durabilityMin;
	private float durabilityMax;
	private boolean durabilitySet;
	private boolean unbreakable;

	/** 记一次词缀需求。 */
	public void addAffix(String affixType, int count) {
		if (count > 0) {
			affixes.merge(affixType, count, Integer::sum);
		}
	}

	/** 设定镶孔区间（后写的覆盖先写的，同神化的规则顺序语义）。 */
	public void setSocketRange(int min, int max) {
		this.socketMin = min;
		this.socketMax = max;
		this.socketsSet = true;
	}

	/** 设定耐久加成区间。 */
	public void setDurabilityRange(float min, float max) {
		this.durabilityMin = min;
		this.durabilityMax = max;
		this.durabilitySet = true;
	}

	public void setUnbreakable(boolean unbreakable) {
		this.unbreakable = unbreakable;
	}

	// ---------------------------------------------------------------- 读取

	/** 词缀计划（类型 → 条数，只读、保序）。 */
	public Map<String, Integer> affixPlan() {
		return Collections.unmodifiableMap(affixes);
	}

	/** 词缀总条数。 */
	public int totalAffixes() {
		return affixes.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** 某类词缀的条数。 */
	public int affixesOf(String affixType) {
		return affixes.getOrDefault(affixType, 0);
	}

	/** 全部词缀类型（按计划顺序展开，一条一次）。 */
	public List<String> affixTypes() {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : affixes.entrySet()) {
			for (int i = 0; i < entry.getValue(); i++) {
				out.add(entry.getKey());
			}
		}
		return out;
	}

	public boolean hasSockets() {
		return socketsSet;
	}

	public int socketMin() {
		return socketMin;
	}

	public int socketMax() {
		return socketMax;
	}

	/** 掷出本次的镶孔数。 */
	public int rollSockets(RandomSource rand) {
		if (!socketsSet || socketMax <= 0) {
			return 0;
		}
		return socketMin + (socketMax > socketMin ? rand.nextInt(socketMax - socketMin + 1) : 0);
	}

	public boolean hasDurability() {
		return durabilitySet;
	}

	public float durabilityMin() {
		return durabilityMin;
	}

	public float durabilityMax() {
		return durabilityMax;
	}

	/** 掷出本次的耐久加成（无视损耗比例，0..1）。 */
	public float rollDurability(RandomSource rand) {
		if (!durabilitySet) {
			return 0.0F;
		}
		return durabilityMax > durabilityMin
			? durabilityMin + rand.nextFloat() * (durabilityMax - durabilityMin)
			: durabilityMin;
	}

	public boolean unbreakable() {
		return unbreakable;
	}

	@Override
	public String toString() {
		return "RarityBuild[affixes=" + affixes + ", sockets=" + (socketsSet ? socketMin + ".." + socketMax : "-")
			+ ", durability=" + (durabilitySet ? durabilityMin + ".." + durabilityMax : "-")
			+ (unbreakable ? ", unbreakable" : "") + "]";
	}
}
