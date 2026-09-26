package api.chasm.item;

/**
 * 支持 Chasm 行为系统的物品统一标记。
 *
 * <p>任何 Chasm 物品子类（{@link ChasmItem} 普通物品、{@link ChasmSwordItem} 剑等）
 * 都携带一个共享的 {@link ChasmItemBehavior}。本接口让
 * {@link ChasmItemController}、{@link api.chasm.context.ModContext} 与
 * {@link api.chasm.registry.ChasmRegistrar} 能对【任意】Chasm 物品做统一的
 * SPI 扩展（追加/覆写行为、读取声明元数据），而无需关心底层具体继承哪个原版类。</p>
 */
public interface ChasmItemSupport {

	/** 返回该物品携带的共享行为载体（不可为 null）。 */
	ChasmItemBehavior chasmBehavior();

	/**
	 * 返回该物品绑定的 {@link ItemType}（未绑定返回 null）。
	 *
	 * <p>用于运行时反查类型（附魔台注入按类型附魔池筛选、装备栏位校验等），
	 * 对任意 Chasm 物品统一生效。</p>
	 */
	default ItemType chasmType() {
		return chasmBehavior().chasmType();
	}
}