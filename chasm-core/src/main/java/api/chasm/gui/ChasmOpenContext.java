package api.chasm.gui;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.Objects;

/**
 * 界面打开（onOpen）回调上下文：服务端在玩家打开界面的瞬间注入。
 *
 * <p>用于数据绑定（L4）：在 onOpen 里给槽位预设物品，随打开瞬间的整包同步
 * 一并出现在客户端。对槽位内容的随后修改会自动经 {@code broadcastChanges}
 * 逐 tick 增量同步。</p>
 *
 * <p>约定：所有读写/结算均持有服务端实体（Player），因此这里看到的玩家、
 * 世界都是服务端权威状态。</p>
 */
public final class ChasmOpenContext {

	private final Player player;
	private final ChasmMenu menu;

	ChasmOpenContext(Player player, ChasmMenu menu) {
		this.player = Objects.requireNonNull(player, "player");
		this.menu = Objects.requireNonNull(menu, "menu");
	}

	/** 打开界面的玩家（服务端实体）。 */
	public Player player() {
		return player;
	}

	/** 服务端正在生成的容器菜单（可读取布局/槽位）。 */
	public ChasmMenu menu() {
		return menu;
	}

	/** 在指定槽位放入物品（随打开同步）。 */
	public void setItem(int slotIndex, ItemStack stack) {
		menu.setItem(slotIndex, stack);
	}

	/** 在指定槽位放入物品（数量为 count，非空堆时必须 &le; 64）。 */
	public void setItem(int slotIndex, ItemLike item, int count) {
		setItem(slotIndex, new ItemStack(item, count));
	}

	/** 在指定槽位放入单件物品。 */
	public void setItem(int slotIndex, ItemLike item) {
		setItem(slotIndex, item, 1);
	}

	/** 读取指定槽位物品。 */
	public ItemStack getItem(int slotIndex) {
		return menu.getItem(slotIndex);
	}
}