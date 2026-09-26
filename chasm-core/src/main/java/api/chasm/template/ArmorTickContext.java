package api.chasm.template;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 护甲套装 tick 上下文：封装一次「已穿戴套装的某件护甲每 tick 更新」的信息。
 *
 * <p>由 {@code ChasmArmorItem.inventoryTick} 在服务端构建并触发（第十四步模板层）。
 * 半套/全套效果回调每 tick 收到一个本上下文，可在其中做持续刷新效果
 * （如 Aquamirae 的「施加 StrongArmor 4t 持续刷新」模式）。</p>
 */
public final class ArmorTickContext {

	private final Player player;
	private final ItemStack stack;
	private final int pieceCount;
	private final Level level;

	public ArmorTickContext(Player player, ItemStack stack, int pieceCount) {
		this.player = player;
		this.stack = stack;
		this.pieceCount = pieceCount;
		this.level = player.level();
	}

	/** 穿戴套装的玩家。 */
	public Player player() {
		return player;
	}

	/** 触发本次 tick 的护甲件（套装中的具体某一件）。 */
	public ItemStack stack() {
		return stack;
	}

	/** 当前已穿戴的本套装件数（含触发件，最多 4）。 */
	public int pieceCount() {
		return pieceCount;
	}

	/** 所在世界（源自玩家）。 */
	public Level level() {
		return level;
	}

	/** 给玩家发送系统消息。 */
	public void sendMessage(String message) {
		if (!level.isClientSide) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
		}
	}

	/** 仅服务端执行（套装效果本就在服务端触发，保留以统一 API）。 */
	public void serverOnly(Runnable action) {
		if (!level.isClientSide) {
			action.run();
		}
	}
}
