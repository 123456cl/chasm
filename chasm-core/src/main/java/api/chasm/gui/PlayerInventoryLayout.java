package api.chasm.gui;

/**
 * **玩家背包布局**（面板像素）—— 服务端建槽、客户端画框、声明式节点定位**共用这一个来源**。
 *
 * <p>为什么要单独拎出来：以前服务端在 {@link ChasmMenu} 里算一遍 "x*CELL+84"，
 * 客户端在 {@code ChasmScreen.renderBg} 里又算一遍，声明式节点再算第三遍 ——
 * 三份算法只要有一处改了间距，立刻就是"槽框和物品错位"。
 * 现在只有这里一份：{@link #slotX(int)}/{@link #slotY(int)}。</p>
 *
 * <p>真实参数（Tetra 的 {@code WorkbenchContainer}）：背包 3 行 {@code x*17+84, y*17+166}，
 * 快捷栏 {@code i*17+84, 221} —— 注意是 **17px** 间距，不是网格的 18px，
 * 而且快捷栏不是紧贴背包（166+3*17=217，快捷栏在 221），照抄网格公式必错位。</p>
 *
 * @param x       背包左上角 x（面板像素）
 * @param bagY    背包第一行 y
 * @param gap     槽间距（像素）
 * @param hotbarY 快捷栏 y
 */
public record PlayerInventoryLayout(int x, int bagY, int gap, int hotbarY) {

	/** 玩家背包槽位总数（27 背包 + 9 快捷栏）。 */
	public static final int SLOTS = 36;

	/**
	 * 由界面声明推导背包布局。
	 *
	 * <p>未声明 {@code playerInventoryAt} 时按网格推导（老界面行为不变）；
	 * 声明了就完全按声明来（移植固定像素 UI 时必须这样）。</p>
	 */
	public static PlayerInventoryLayout of(ChasmGui gui) {
		int gap = gui.playerInvSpacing() > 0 ? gui.playerInvSpacing() : ChasmMenu.CELL;
		boolean explicit = gui.playerInvX() != 0 || gui.playerInvY() != 0;
		int x = explicit ? gui.playerInvX() : ChasmMenu.GRID_X;
		int bagY = explicit ? gui.playerInvY() : ChasmMenu.GRID_Y + gui.rows() * ChasmMenu.CELL + 14;
		// 快捷栏：显式声明优先；否则"背包整体下方留 4px"
		int hotbarY = gui.hotbarY() > 0 ? gui.hotbarY() : bagY + 3 * gap + 4;
		return new PlayerInventoryLayout(x, bagY, gap, hotbarY);
	}

	/** 该背包索引（0..8 快捷栏，9..35 背包）的 x。 */
	public int slotX(int inventoryIndex) {
		int column = inventoryIndex < 9 ? inventoryIndex : (inventoryIndex - 9) % 9;
		return x + column * gap;
	}

	/** 该背包索引的 y。 */
	public int slotY(int inventoryIndex) {
		return inventoryIndex < 9 ? hotbarY : bagY + (inventoryIndex - 9) / 9 * gap;
	}
}
