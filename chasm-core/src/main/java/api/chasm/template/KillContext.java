package api.chasm.template;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ItemLike;

/**
 * 击杀玩法上下文：封装一次「玩家用本物品击杀实体」的全部信息与便捷操作。
 *
 * <p>由服务端 {@code ServerLivingEntityEvents.AFTER_DEATH} 监听器构建（第十四步模板层）。
 * 事件本身只在服务端主线程触发，因此本上下文内无需再做 {@code serverOnly} 双端分发；
 * 仍保留 {@link #serverOnly(Runnable)} 便捷方法以统一心智。</p>
 */
public final class KillContext {

	private final ItemStack stack;
	private final net.minecraft.world.entity.LivingEntity victim;
	private final Player killer;
	private final DamageSource source;

	public KillContext(ItemStack stack, net.minecraft.world.entity.LivingEntity victim,
		Player killer, DamageSource source) {
		this.stack = stack;
		this.victim = victim;
		this.killer = killer;
		this.source = source;
	}

	/** 击杀武器（击杀者主手物品栈）。 */
	public ItemStack stack() {
		return stack;
	}

	/** 被击杀的实体。 */
	public net.minecraft.world.entity.LivingEntity victim() {
		return victim;
	}

	/** 击杀者（玩家）。 */
	public Player killer() {
		return killer;
	}

	/** 本次击杀的伤害来源（可读取死亡消息/标签等）。 */
	public DamageSource source() {
		return source;
	}

	/** 所在世界（源自击杀者）。 */
	public Level level() {
		return killer.level();
	}

	/** 仅服务端执行（AFTER_DEATH 本就在服务端触发，此方法恒直接执行，保留以统一 API）。 */
	public void serverOnly(Runnable action) {
		action.run();
	}

	/** 给击杀者发送系统消息。 */
	public void sendMessage(String message) {
		killer.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
	}

	/**
	 * 在击杀位置生成一个掉落物（数量 1，始终掉落）。
	 */
	public void spawnItemDrop(ItemLike item) {
		spawnItemDrop(item, 1);
	}

	/**
	 * 在击杀位置生成一个掉落物（指定数量，始终掉落）。
	 */
	public void spawnItemDrop(ItemLike item, int count) {
		if (count <= 0 || item == null) {
			return;
		}
		ItemStack drop = new ItemStack(item, count);
		if (drop.isEmpty()) {
			return;
		}
		Level world = level();
		ItemEntity entity = new ItemEntity(world,
			victim.getX(), victim.getY() + 0.5, victim.getZ(), drop);
		entity.setDeltaMovement(
			world.random.nextDouble() * 0.2 - 0.1,
			0.2 + world.random.nextDouble() * 0.1,
			world.random.nextDouble() * 0.2 - 0.1);
		entity.setPickUpDelay(10);
		world.addFreshEntity(entity);
	}
}
