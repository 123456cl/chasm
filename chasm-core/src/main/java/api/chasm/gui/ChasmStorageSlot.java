package api.chasm.gui;

import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 一个物品存储槽位（真实 Container 槽，可放物品）。
 *
 * <p><b>物品过滤</b>：filter 非空时只有满足条件的物品能放进该槽（原版 Slot.mayPlace 语义）。
 * 没有过滤就等于"什么都能塞" —— 这正是以前移植时被吐槽的「只该放某类东西的槽位结果啥都能放」。</p>
 *
 * <p><b>2026-09-20 新增：位置可以由"已同步状态"现算</b>（{@code dynamicX}/{@code dynamicY}）。
 * 来源是真实需求 —— Tetra 的工具带界面槽位是**运行时算出来的**：
 * {@code ToolbeltContainer.java:36-71} 里 storage 固定在 y=108，quiver/potion/quick 依次
 * {@code y = 108 - offset} 往上堆，{@code offset += rows*17+13}（storage）或 {@code 30}（其余），
 * 而 {@code rows/cols} 取决于该工具带装了几个附件模块。**静态声明表达不了这种布局**。
 * 见 {@code ChasmGuiBuilder#slotPxDynamic}。</p>
 *
 * @param index    在界面容器中的索引（0..n-1）
 * @param col      所在列（0 起始，网格单元）
 * @param row      所在行（0 起始，网格单元）
 * @param filter   允许放入的物品判定（null = 不过滤）
 * @param pixelRect 像素矩形（null = 用网格 col/row 推导）
 * @param visibility 该槽此刻是否可见/可用（null = 恒可见）
 * @param dynamicX 位置 x 的现算函数（输入是当前菜单，读同步状态返回面板像素 x；null = 用固定位置）
 * @param dynamicY 位置 y 的现算函数（同上）
 */
public record ChasmStorageSlot(int index, int col, int row, Predicate<ItemStack> filter, GuiRect pixelRect,
							   Predicate<ChasmMenu> visibility, ToIntFunction<ChasmMenu> dynamicX,
							   ToIntFunction<ChasmMenu> dynamicY) {

	/**
	 * 固定位置的规范构造器（**旧签名原样保留**：既有调用点一个字都不用改）。
	 */
	public ChasmStorageSlot(int index, int col, int row, Predicate<ItemStack> filter, GuiRect pixelRect,
							Predicate<ChasmMenu> visibility) {
		this(index, col, row, filter, pixelRect, visibility, null, null);
	}

	/** 像素级槽位（移植别人固定像素 UI 时用）。 */
	public ChasmStorageSlot(int index, GuiRect pixelRect, Predicate<ItemStack> filter) {
		this(index, 0, 0, filter, pixelRect, null);
	}

	/** 面板相对像素 x（有像素矩形时优先）。 */
	public int pixelX() {
		return pixelRect != null ? pixelRect.x() : ChasmMenu.GRID_X + col * ChasmMenu.CELL;
	}

	/** 面板相对像素 y（有像素矩形时优先）。 */
	public int pixelY() {
		return pixelRect != null ? pixelRect.y() : ChasmMenu.GRID_Y + row * ChasmMenu.CELL;
	}

	/**
	 * **带菜单的像素 x**：声明了 {@code dynamicX} 就现算，否则退回固定位置。
	 *
	 * <p>菜单为 null（测试/离屏构建）时也退回固定位置，**绝不抛**。</p>
	 */
	public int pixelX(ChasmMenu menu) {
		return dynamicX != null && menu != null ? dynamicX.applyAsInt(menu) : pixelX();
	}

	/** **带菜单的像素 y**（语义同 {@link #pixelX(ChasmMenu)}）。 */
	public int pixelY(ChasmMenu menu) {
		return dynamicY != null && menu != null ? dynamicY.applyAsInt(menu) : pixelY();
	}

	/** 位置是否由状态现算（用于"数据变了要重建槽位"的判定）。 */
	public boolean isDynamic() {
		return dynamicX != null || dynamicY != null;
	}

	/** 不过滤的槽位（向后兼容）。 */
	public ChasmStorageSlot(int index, int col, int row) {
		this(index, col, row, null, null, null);
	}

	/** 网格槽位 + 过滤。 */
	public ChasmStorageSlot(int index, int col, int row, Predicate<ItemStack> filter) {
		this(index, col, row, filter, null, null);
	}

	/** 像素槽位 + 过滤 + 可见性。 */
	public ChasmStorageSlot(int index, GuiRect pixelRect, Predicate<ItemStack> filter,
							Predicate<ChasmMenu> visibility) {
		this(index, 0, 0, filter, pixelRect, visibility);
	}

	/** 该物品能否放入本槽。 */
	public boolean mayPlace(ItemStack stack) {
		return filter == null || filter.test(stack);
	}

	/**
	 * **本槽当前是否可见/可用**（服务端与客户端各自用**同一份已同步状态**判定，结论一致）。
	 *
	 * <p>来源是真实需求：Tetra 的材料槽是 {@code ToggleableSlot} —— 没选中蓝图时
	 * {@code getNumMaterialSlots()==0}，三个材料槽全部不可见。没有这个能力，移植时只能让
	 * "只在某个状态下才该出现"的槽位一直杵在界面上（这正是画面里元素"错位/多余"的常见来源）。</p>
	 *
	 * <p>不可见的槽位在客户端不绘制、不参与悬停与点击，也不能被 Shift 快捷移动填进去。</p>
	 */
	public boolean isActive(ChasmMenu menu) {
		return visibility == null || visibility.test(menu);
	}
}
