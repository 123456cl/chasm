package api.chasm.gui.decl;

import api.chasm.gui.ChasmMenu;

import net.minecraft.world.item.ItemStack;

/**
 * {@link GuiSource} 构建节点时能看到的**状态**：菜单里的物品 + 同步过来的整数键。
 *
 * <p>服务端与客户端拿到的是**同一份状态**（物品栏内容与 data 键都同步），
 * 因此两边重建出的节点列表一致 —— 客户端点的是"第 N 个节点"，
 * 服务端重建后查到的也是同一个节点，不需要传坐标、不会错位。</p>
 */
public final class GuiState {

	private final ChasmMenu menu;

	public GuiState(ChasmMenu menu) {
		this.menu = menu;
	}

	public ItemStack item(int slot) {
		return menu.getItem(slot);
	}

	public boolean hasData(String key) {
		return menu.hasData(key);
	}

	public int data(String key) {
		return menu.hasData(key) ? menu.data(key) : 0;
	}

	public ChasmMenu menu() {
		return menu;
	}
}
