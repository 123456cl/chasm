package api.chasm.item.context;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 玩法行为上下文：封装一次交互（如右键使用）的全部信息与便捷操作。
 *
 * <p>创作者在行为回调中通过本对象访问玩家/世界/物品，并执行消耗、挥臂等玩法动作。</p>
 *
 * <p><b>包级解耦</b>：本类与 {@link AttackContext}/{@link InventoryTickContext}/{@link KeyContext}
 * 一起放在 {@code api.chasm.item.context} 子包，供 {@code api.chasm.item}（行为载体）与
 * {@code api.chasm.trait}/{@code api.chasm.key}（回调侧）共同依赖，打破「物品 ↔ 特质」、
 * 「物品 ↔ 按键」两处包级循环依赖。</p>
 */
public final class UseContext {

	private final Level level;
	private final Player player;
	private final InteractionHand hand;
	private final ItemStack stack;

	public UseContext(Level level, Player player, InteractionHand hand, ItemStack stack) {
		this.level = level;
		this.player = player;
		this.hand = hand;
		this.stack = stack;
	}

	/** 当前所在世界。 */
	public Level level() {
		return level;
	}

	/** 触发行为的玩家。 */
	public Player player() {
		return player;
	}

	/** 使用的手。 */
	public InteractionHand hand() {
		return hand;
	}

	/** 使用的物品栈。 */
	public ItemStack stack() {
		return stack;
	}

	/** 给玩家发送系统消息（仅服务端）。 */
	public void sendMessage(String message) {
		if (!level.isClientSide) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
		}
	}

	/** 仅服务端执行（客户端自动忽略）。 */
	public void serverOnly(Runnable action) {
		if (!level.isClientSide) {
			action.run();
		}
	}

	/** 仅客户端执行（服务端自动忽略）。 */
	public void clientOnly(Runnable action) {
		if (level.isClientSide) {
			action.run();
		}
	}

	/** 挥动手臂（播放使用动画）。 */
	public void swingArm() {
		player.swing(hand);
	}

	/** 消耗 1 个物品（数量减一）。 */
	public void consumeItem() {
		stack.shrink(1);
	}

	/** 消耗指定数量的物品。 */
	public void consumeItem(int count) {
		stack.shrink(count);
	}

	/**
	 * 给物品造成耐久损耗（不可用/损坏时返回 false）。
	 *
	 * <p>走统一入口 {@link api.chasm.item.ChasmDurability#damage}：宝石/词缀声明的
	 * "无视 N% 耐久损耗"（{@code chasm:on_durability_loss}）在这里生效。</p>
	 */
	public boolean damageItem(int amount) {
		return api.chasm.item.ChasmDurability.damage(stack, amount);
	}
}
