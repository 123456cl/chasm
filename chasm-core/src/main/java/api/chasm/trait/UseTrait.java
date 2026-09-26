package api.chasm.trait;

import api.chasm.item.context.UseContext;
import net.minecraft.world.InteractionResult;

/**
 * 右键使用特质（原版 {@code Item.use} 切入点）。
 *
 * <p>函数式接口：创作者以 lambda 实现，注册到 {@link ChasmTraits} 后，
 * 通过 {@code ItemBuilder.useTrait(...)} 绑定到物品，运行时由 {@code ChasmItem} 触发。</p>
 *
 * <p>返回的 {@link InteractionResult} 决定原版如何处理本次使用：
 * {@code SUCCESS} → 成功，{@code CONSUME} → 消耗，{@code FAIL} → 失败，其余 → 放行。</p>
 */
@FunctionalInterface
public interface UseTrait extends ItemTrait {

	/** 触发右键使用行为。 */
	InteractionResult trigger(UseContext ctx);
}