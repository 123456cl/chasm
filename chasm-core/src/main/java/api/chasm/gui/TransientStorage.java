package api.chasm.gui;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** 临时存储：关窗把内容归还玩家背包（旧行为）。 */
final class TransientStorage implements ChasmStorage {

	private final SimpleContainer container;

	TransientStorage(int size) {
		this.container = new SimpleContainer(Math.max(0, size));
	}

	@Override
	public Container container() {
		return container;
	}

	@Override
	public boolean returnsItemsOnClose() {
		return true;
	}

	@Override
	public void onClosed(Player player) {
		if (player == null) {
			return;
		}
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty()) {
				player.getInventory().placeItemBackInInventory(stack);
			}
		}
	}
}
