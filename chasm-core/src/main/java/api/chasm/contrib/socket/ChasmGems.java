package api.chasm.contrib.socket;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宝石登记表：{@code 宝石物品 → 贡献者 id}。
 *
 * <p>宝石的加成不在宝石里手写，而是"宝石 = 一个 {@link api.chasm.contrib.ItemContributor}"：
 * 镶嵌时把其贡献者挂到装备栈上，拔出时摘掉——**不新增任何一套加成/事件机制**，
 * 与词缀共用同一条 {@link api.chasm.contrib.ItemContributions} 管线。</p>
 */
public final class ChasmGems {

	/** 宝石栈上的直接声明（优先级高于物品登记，便于数据包/掉落物携带）。 */
	private static final Map<Item, ResourceLocation> BY_ITEM = new ConcurrentHashMap<>();

	private ChasmGems() {
	}

	/** 登记宝石物品 → 贡献者 id（幂等覆盖）。 */
	public static void register(Item gem, ResourceLocation contributorId) {
		if (gem == null || contributorId == null) {
			throw new IllegalArgumentException("宝石与贡献者 id 不可为 null");
		}
		BY_ITEM.put(gem, contributorId);
	}

	/** 查询某宝石栈对应的贡献者 id（物品登记 / 栈组件都能用；无则 null）。 */
	public static ResourceLocation contributorOf(ItemStack gem) {
		if (gem == null || gem.isEmpty()) {
			return null;
		}
		ResourceLocation fromStack = gem.get(api.chasm.data.ChasmData.GEM_CONTRIBUTOR);
		if (fromStack != null) {
			return fromStack;
		}
		ResourceLocation fromRegistry = BY_ITEM.get(gem.getItem());
		if (fromRegistry == null) {
			ChasmLogger.debug("chasm", "宝石 {} 未登记贡献者，镶嵌后不产生加成", gem.getItem());
		}
		return fromRegistry;
	}
}
