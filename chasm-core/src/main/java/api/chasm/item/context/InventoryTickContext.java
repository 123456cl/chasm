package api.chasm.item.context;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 物品栏 tick 上下文：封装一次「物品在物品栏中每 tick 更新」的信息。
 *
 * <p>创作者在 {@code INVENTORY_TICK} 特质中通过本对象访问玩家/世界/物品，
 * 实现持续效果（如持有物品时每秒回蓝）。</p>
 *
 * <p><b>包级解耦</b>：本类放在 {@code api.chasm.item.context} 子包，供
 * {@code api.chasm.item}（行为载体）与 {@code api.chasm.trait}（回调侧）共同依赖，
 * 打破「物品 ↔ 特质」包级循环依赖。</p>
 */
public final class InventoryTickContext {

	private final Level level;
	private final Player player;
	private final ItemStack stack;
	private final int slotId;
	private final boolean selected;

	public InventoryTickContext(Level level, Player player, ItemStack stack, int slotId, boolean selected) {
		this.level = level;
		this.player = player;
		this.stack = stack;
		this.slotId = slotId;
		this.selected = selected;
	}

	/** 当前所在世界。 */
	public Level level() {
		return level;
	}

	/** 持有该物品的玩家。 */
	public Player player() {
		return player;
	}

	/** 正在 tick 的物品栈。 */
	public ItemStack stack() {
		return stack;
	}

	/** 物品所在槽位 id。 */
	public int slotId() {
		return slotId;
	}

	/** 该物品是否处于当前选中（手持）槽。 */
	public boolean selected() {
		return selected;
	}

	/** 是否运行在服务端（法力等数据操作只应在服务端执行）。 */
	public boolean isServer() {
		return !level.isClientSide;
	}

	/** 给持有者发送系统消息（仅服务端 + 玩家）。 */
	public void sendMessage(String message) {
		if (!level.isClientSide) {
			player.sendSystemMessage(Component.literal(message));
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
}
