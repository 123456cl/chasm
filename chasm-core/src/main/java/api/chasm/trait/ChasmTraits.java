package api.chasm.trait;

import api.chasm.item.context.AttackContext;
import api.chasm.item.context.InventoryTickContext;
import api.chasm.item.context.UseContext;
import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行为特质全局注册表（单例）。
 *
 * <p>按 {@link ItemTraitEvent} 分桶维护「特质 id → 特质实例」映射，每个事件类型一个
 * 独立 {@code ConcurrentHashMap}。查找为 O(1)，无遍历。</p>
 *
 * <p>注册返回 {@link ResourceLocation}（形如 {@code modId:name}），供 {@code ItemBuilder}
 * 绑定到物品栈的 Trait ID 数据组件；运行时由 {@code ChasmItem} 取出 id、O(1) 查回实例并执行。</p>
 *
 * <p>当前上线 {@link ItemTraitEvent#USE} 与 {@link ItemTraitEvent#ATTACK}；
 * {@link ItemTraitEvent} 其余枚举为架构预留，本注册表骨架已通用支持，后续只需补
 * 数据组件 + Builder 绑定方法 + {@code ChasmItem} 方法覆写，无需改动本类。</p>
 */
public final class ChasmTraits {

	/** 全局单例。 */
	public static final ChasmTraits INSTANCE = new ChasmTraits();

	private final Map<ItemTraitEvent, Map<ResourceLocation, ItemTrait>> registry =
		new EnumMap<>(ItemTraitEvent.class);

	private ChasmTraits() {
		// 预初始化每个事件类型的桶，避免运行时并发创建
		for (ItemTraitEvent event : ItemTraitEvent.values()) {
			registry.put(event, new ConcurrentHashMap<>());
		}
	}

	/** 注册一个右键使用特质。 */
	public ResourceLocation registerUse(String modId, String name, UseTrait trait) {
		return register(ItemTraitEvent.USE, modId, name, trait);
	}

	/** 注册一个攻击实体特质。 */
	public ResourceLocation registerAttack(String modId, String name, AttackTrait trait) {
		return register(ItemTraitEvent.ATTACK, modId, name, trait);
	}

	/** 注册一个物品栏 tick 特质。 */
	public ResourceLocation registerInventoryTick(String modId, String name, InventoryTickTrait trait) {
		return register(ItemTraitEvent.INVENTORY_TICK, modId, name, trait);
	}

	/**
	 * 通用注册：把特质登记到指定事件类型的桶中。
	 *
	 * @param event 事件类型
	 * @param modId 模组命名空间
	 * @param name  特质注册名
	 * @param trait 特质实例
	 * @param <T>   特质类型
	 * @return 特质 id（{@code modId:name}）
	 */
	public <T extends ItemTrait> ResourceLocation register(ItemTraitEvent event, String modId, String name, T trait) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		registry.get(event).put(id, trait);
		ChasmLogger.info(modId, "注册 {} 特质: {}", event.code(), id);
		return id;
	}

	/**
	 * 按事件类型与 id 查询特质。
	 *
	 * <p>性能优化（第十步）：直接返回特质实例或 {@code null}，移除 {@code Optional} 包装，
	 * 避免高频攻击/右键场景下的装箱与 GC 开销。调用方用 {@code != null} 判断即可。</p>
	 *
	 * @param event 事件类型
	 * @param id    特质 id（注册时返回的 ResourceLocation）
	 * @param <T>   特质类型（由调用方按事件类型约定）
	 * @return 特质实例；未注册返回 {@code null}
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T extends ItemTrait> T get(ItemTraitEvent event, ResourceLocation id) {
		return (T) registry.get(event).get(id);
	}
}