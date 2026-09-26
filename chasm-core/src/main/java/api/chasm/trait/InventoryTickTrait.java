package api.chasm.trait;

import api.chasm.item.context.InventoryTickContext;

/**
 * 物品栏每 tick 特质（原版 {@code Item.inventoryTick} 切入点）。
 *
 * <p>当物品处于某实体的物品栏中时，每 tick 触发一次（无论是否选中）。
 * 常用于持续效果，如「持有该物品时每秒回蓝」。</p>
 *
 * <p>与 {@link UseTrait}/{@link AttackTrait} 不同，本特质 {@code trigger} 无返回值
 * （原版 {@code inventoryTick} 本身就是 void），不参与原版交互结果判定。</p>
 */
@FunctionalInterface
public interface InventoryTickTrait extends ItemTrait {

	/** 触发物品栏 tick 行为。 */
	void trigger(InventoryTickContext ctx);
}