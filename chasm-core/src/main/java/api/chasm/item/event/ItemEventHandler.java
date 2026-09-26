package api.chasm.item.event;

import net.minecraft.world.item.ItemStack;

/**
 * 事件处理器（挂在物品栈上，经处理器注册表按 id 反查）。
 *
 * @param <P> 事件负载（事件源提供的上下文）
 * @param <R> 结果类型（与 {@link ItemEventKey} 的协议一致）
 */
@FunctionalInterface
public interface ItemEventHandler<P, R> {
	/** 处理一次事件：读取负载与当前累计结果，返回新的结果。 */
	R handle(P payload, ItemStack stack, R current);
}
