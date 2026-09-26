package api.chasm.item.context;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 攻击玩法行为上下文：封装一次用物品攻击实体的信息。
 *
 * <p><b>包级解耦</b>：本类放在 {@code api.chasm.item.context} 子包，供
 * {@code api.chasm.item}（行为载体）与 {@code api.chasm.trait}（回调侧）共同依赖，
 * 打破「物品 ↔ 特质」包级循环依赖。</p>
 */
public final class AttackContext {

	private final ItemStack stack;
	private final LivingEntity target;
	private final LivingEntity attacker;

	public AttackContext(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		this.stack = stack;
		this.target = target;
		this.attacker = attacker;
	}

	/** 使用的物品栈。 */
	public ItemStack stack() {
		return stack;
	}

	/** 被攻击的目标实体。 */
	public LivingEntity target() {
		return target;
	}

	/** 发起攻击的实体（通常为玩家）。 */
	public LivingEntity attacker() {
		return attacker;
	}

	/** 所在世界（源自攻击者）。 */
	public net.minecraft.world.level.Level level() {
		return attacker.level();
	}

	/** 当前是否运行在服务端。 */
	public boolean isServer() {
		return !attacker.level().isClientSide;
	}

	/** 仅服务端执行（客户端自动忽略）。 */
	public void serverOnly(Runnable action) {
		if (!attacker.level().isClientSide) {
			action.run();
		}
	}

	/** 给攻击者发送系统消息（仅服务端 + 玩家）。 */
	public void sendMessage(String message) {
		if (attacker instanceof net.minecraft.world.entity.player.Player player
			&& !attacker.level().isClientSide) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
		}
	}

	/** 消耗攻击者物品耐久（走统一耐久入口，宝石/词缀的"无视损耗"在这里生效）。 */
	public void damageItem(int amount) {
		api.chasm.item.ChasmDurability.damage(stack, amount);
	}
}
