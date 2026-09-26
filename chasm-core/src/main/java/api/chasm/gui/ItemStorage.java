package api.chasm.gui;

import api.chasm.log.ChasmLogger;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * 物品自带容器存储：内容存进物品栈的 {@code minecraft:container} 组件（原版格式，整合包/其他模组可读）。
 *
 * <p>关窗写回；若物品已不在玩家身上（被丢出/被拿走），则把内容**掉落到地面**而不是凭空消失。</p>
 *
 * <p>另有 {@link #FLUSH_INTERVAL_TICKS} 的周期性落盘：只在内容真的变过时写入，
 * 把"服务端崩溃丢内容"的窗口压到几秒内。</p>
 */
final class ItemStorage implements ChasmStorage {

	/** 周期性落盘间隔（tick）。 */
	private static final int FLUSH_INTERVAL_TICKS = 40;

	private final ItemStack stack;
	private final SimpleContainer container;
	private boolean dirty;
	private int ticksSinceFlush;

	ItemStorage(ItemStack stack, int size) {
		if (stack == null || stack.isEmpty()) {
			throw new IllegalArgumentException("物品存储需要非空物品栈");
		}
		this.stack = stack;
		this.container = new SimpleContainer(Math.max(0, size)) {
			@Override
			public void setChanged() {
				super.setChanged();
				dirty = true;
			}
		};
		// 载入已有内容（原版组件格式）
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents != null) {
			NonNullList<ItemStack> items = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
			contents.copyInto(items);
			for (int i = 0; i < items.size(); i++) {
				container.setItem(i, items.get(i));
			}
			dirty = false;
		}
	}

	@Override
	public Container container() {
		return container;
	}

	@Override
	public boolean returnsItemsOnClose() {
		return false;
	}

	@Override
	public void tick(Player player) {
		if (!dirty) {
			return;
		}
		if (++ticksSinceFlush >= FLUSH_INTERVAL_TICKS) {
			ticksSinceFlush = 0;
			onClosed(player);
		}
	}

	@Override
	public void onClosed(Player player) {
		if (!dirty) {
			return;
		}
		ticksSinceFlush = 0;
		dirty = false;
		// 物品已不在身上（被丢出/被转移）→ 内容掉落到地面，避免凭空消失
		if (player != null && !stillCarried(player)) {
			for (int i = 0; i < container.getContainerSize(); i++) {
				ItemStack item = container.getItem(i);
				if (!item.isEmpty()) {
					player.drop(item.copy(), false);
					container.setItem(i, ItemStack.EMPTY);
				}
			}
			ChasmLogger.warn("chasm", "便携容器 {} 关窗时已不在玩家身上，内容已掉落到地面", stack.getItem());
			return;
		}
		writeToStack();
	}

	/** 立刻写回物品（测试与显式落盘用）。 */
	void writeToStack() {
		ItemStack[] items = new ItemStack[container.getContainerSize()];
		boolean any = false;
		for (int i = 0; i < items.length; i++) {
			items[i] = container.getItem(i).copy();
			any |= !items[i].isEmpty();
		}
		if (any) {
			stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(java.util.List.of(items)));
		} else {
			stack.remove(DataComponents.CONTAINER);
		}
	}

	/** 该栈是否仍被玩家携带（主背包/快捷栏/副手，按对象身份判断）。 */
	private boolean stillCarried(Player player) {
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i) == stack) {
				return true;
			}
		}
		return player.getOffhandItem() == stack;
	}
}
