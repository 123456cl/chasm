package api.chasm.trait;

/**
 * 物品行为特质的事件类型。
 *
 * <p>一个事件类型对应一个原版方法切入点与一个独立的 Trait 注册表。
 * 当前第三步仅实现并接线 {@link #USE} 与 {@link #ATTACK}；其余事件类型作为
 * 架构预留（枚举已就位、注册表泛型已支持），后续步骤只需补：
 * 对应数据组件 + Builder 绑定方法 + {@code ChasmItem} 方法覆写，无需改注册表骨架。</p>
 */
public enum ItemTraitEvent {

	/** 右键使用物品（原版 {@code Item.use}）。 */
	USE("use"),
	/** 用物品攻击实体（原版 {@code Item.hurtEnemy}）。 */
	ATTACK("attack"),

	// —— 预留扩展位（后续步骤实现，当前不接线） ——

	/** 物品栏每 tick 更新（原版 {@code Item.inventoryTick}）。 */
	INVENTORY_TICK("inventory_tick"),
	/** 用物品交互实体（原版 {@code Item.interactLivingEntity}）。 */
	ENTITY_INTERACT("entity_interact"),
	/** 用物品交互方块（原版 {@code Item.useOn}）。 */
	BLOCK_INTERACT("block_interact"),
	/** 使用完成（原版 {@code Item.finishUsing}）。 */
	FINISH_USING("finish_using"),
	/** 自定义按键动作（后续网络模块启用后接线）。 */
	CUSTOM_ACTION("custom_action");

	private final String code;

	ItemTraitEvent(String code) {
		this.code = code;
	}

	/** 事件类型的小写代码（用于日志与注册名）。 */
	public String code() {
		return code;
	}
}