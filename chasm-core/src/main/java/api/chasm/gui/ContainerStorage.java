package api.chasm.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;

/** 外部容器存储：界面直接读写调用方给的容器（方块实体等），关窗不搬运。 */
final class ContainerStorage implements ChasmStorage {

	private final Container container;

	ContainerStorage(Container container) {
		if (container == null) {
			throw new IllegalArgumentException("外部容器不可为 null");
		}
		this.container = container;
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
	public void onClosed(Player player) {
		// 内容归容器自己管（方块实体会自行存档）
	}
}
