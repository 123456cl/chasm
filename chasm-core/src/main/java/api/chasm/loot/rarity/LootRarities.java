package api.chasm.loot.rarity;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 稀有度注册表（**开放**）：附属模组可加自己的层级（"远古"乃至"神话之上"）。 */
public final class LootRarities {

	private static final Map<ResourceLocation, LootRarity> REGISTRY = new ConcurrentHashMap<>();
	private static volatile List<LootRarity> sorted = List.of();

	private LootRarities() {
	}

	/** 注册（幂等覆盖；按 sortIndex 维护有序快照）。 */
	public static synchronized LootRarity register(LootRarity rarity) {
		if (rarity == null) {
			throw new IllegalArgumentException("稀有度不可为 null");
		}
		LootRarity old = REGISTRY.put(rarity.id(), rarity);
		if (old != null && old != rarity) {
			ChasmLogger.debug("chasm", "稀有度 {} 被重复注册（已覆盖）", rarity.id());
		}
		List<LootRarity> next = new ArrayList<>(REGISTRY.values());
		next.sort(Comparator.comparingInt(LootRarity::sortIndex));
		sorted = Collections.unmodifiableList(next);
		return rarity;
	}

	public static LootRarity get(ResourceLocation id) {
		return id == null ? null : REGISTRY.get(id);
	}

	/** 按路径取（组件里存路径，省空间）。 */
	public static LootRarity byPath(String path) {
		if (path == null) {
			return null;
		}
		LootRarity direct = REGISTRY.get(ResourceLocation.tryParse(path));
		if (direct != null) {
			return direct;
		}
		for (Map.Entry<ResourceLocation, LootRarity> entry : REGISTRY.entrySet()) {
			if (entry.getKey().getPath().equals(path)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/** 全部稀有度（按 sortIndex 升序）。 */
	public static List<LootRarity> all() {
		return sorted;
	}

	public static int size() {
		return REGISTRY.size();
	}

	public static LootRarity lowest() {
		return sorted.isEmpty() ? null : sorted.get(0);
	}

	public static LootRarity highest() {
		return sorted.isEmpty() ? null : sorted.get(sorted.size() - 1);
	}

	/** 不低于给定稀有度的全部层级（含自身，按 sortIndex）。 */
	public static List<LootRarity> atLeast(LootRarity rarity) {
		if (rarity == null) {
			return all();
		}
		return sorted.stream().filter(r -> r.sortIndex() >= rarity.sortIndex()).toList();
	}

	/** 在世界自然掉落里按权重抽一个稀有度（全零权重则均匀）。 */
	public static LootRarity roll(RandomSource rand) {
		List<LootRarity> pool = sorted;
		if (pool.isEmpty()) {
			return null;
		}
		double total = pool.stream().mapToDouble(LootRarity::weight).sum();
		if (total <= 0) {
			return pool.get(rand.nextInt(pool.size()));
		}
		double r = rand.nextDouble() * total;
		for (LootRarity rarity : pool) {
			r -= rarity.weight();
			if (r <= 0) {
				return rarity;
			}
		}
		return pool.get(pool.size() - 1);
	}
}
