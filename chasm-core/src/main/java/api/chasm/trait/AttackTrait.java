package api.chasm.trait;

import api.chasm.item.context.AttackContext;
import net.minecraft.world.InteractionResult;

/**
 * 攻击实体特质（原版 {@code Item.hurtEnemy} 切入点）。
 *
 * <p>返回 {@code SUCCESS} 或 {@code CONSUME} 表示已处理
 * （不再走原版伤害逻辑）；返回 {@code PASS/FAIL} 则交由原版继续处理。</p>
 */
@FunctionalInterface
public interface AttackTrait extends ItemTrait {

	/** 触发攻击行为。 */
	InteractionResult trigger(AttackContext ctx);
}