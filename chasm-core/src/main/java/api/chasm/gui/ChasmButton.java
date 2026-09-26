package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * 一个声明式按钮（界面网格中的虚拟按钮，不占用物品槽位）。
 *
 * <p>由 {@link ChasmGuiBuilder} 创建，不可变。</p>
 *
 * <p>除纯文字按钮外，还支持纹理图标与点击冷却：</p>
 * <ul>
 *   <li>{@code label}：按钮文字（无图标时绘制；命中校验仍用 label 与服务端比对）</li>
 *   <li>{@code col}/{@code row}：所在网格单元（0 起始）</li>
 *   <li>{@code handler}：点击回调（服务端主线程）</li>
 *   <li>{@code icon}：图标贴图（可为 null，null 表示纯文字按钮）</li>
 *   <li>{@code iconU}/{@code iconV}：图标贴图正屏帧左上角格子坐标。约定图标贴图为
 *       32x32，上下两帧：正常帧在 {@code (iconU, iconV)}，悬停帧在 {@code (iconU, iconV+16)}。</li>
 *   <li>{@code cooldownTicks}：该按钮点击冷却（tick 数），0 表示沿用服务端全局下限。</li>
 * </ul>
 */
public final class ChasmButton {

	private final String label;
	private final int col;
	private final int row;
	private final ChasmButtonHandler handler;
	private final ResourceLocation icon;
	private final int iconU;
	private final int iconV;
	private final int cooldownTicks;
	private final int widthCells;
	private final int heightCells;
	private final GuiRect pixelRect;   // 非空 = 用像素布局（移植别人 UI 用），否则用网格

	/**
	 * 完整构造器。
	 *
	 * @param label         按钮文字（非空）
	 * @param col           所在列（0 起始）
	 * @param row           所在行（0 起始）
	 * @param handler       点击回调（非空）
	 * @param icon          图标贴图（可为 null，null 即纯文字按钮）
	 * @param iconU         图标贴图正常帧左上角 U 坐标
	 * @param iconV         图标贴图正常帧左上角 V 坐标
	 * @param cooldownTicks 该按钮点击冷却（tick 数；0 表示沿用服务端全局下限）
	 */
	public ChasmButton(String label, int col, int row, ChasmButtonHandler handler,
		ResourceLocation icon, int iconU, int iconV, int cooldownTicks) {
		this(label, col, row, 1, 1, handler, icon, iconU, iconV, cooldownTicks);
	}

	/**
	 * 完整构造器（含**自定义宽高**，单位为网格单元）。
	 *
	 * <p>宽度很重要：文字比按钮宽就会溢出、与相邻按钮重叠。用
	 * {@link ChasmGuiText#cells(String)} 可以按文字长度自动算宽度。</p>
	 *
	 * @param widthCells  宽（格，≥1）
	 * @param heightCells 高（格，≥1）
	 */
	public ChasmButton(String label, int col, int row, int widthCells, int heightCells,
		ChasmButtonHandler handler, ResourceLocation icon, int iconU, int iconV, int cooldownTicks) {
		this(label, col, row, widthCells, heightCells, null, handler, icon, iconU, iconV, cooldownTicks);
	}

	/**
	 * 全网参构造：可指定**像素矩形**（移植别人固定像素 UI 时用；非空则忽略 col/row/格数）。
	 */
	public ChasmButton(String label, int col, int row, int widthCells, int heightCells, GuiRect pixelRect,
		ChasmButtonHandler handler, ResourceLocation icon, int iconU, int iconV, int cooldownTicks) {
		this.pixelRect = pixelRect;
		if (widthCells < 1 || heightCells < 1) {
			throw new IllegalArgumentException("按钮 " + label + " 的宽高至少 1 格，收到 "
				+ widthCells + "x" + heightCells);
		}
		this.widthCells = widthCells;
		this.heightCells = heightCells;
		this.label = Objects.requireNonNull(label, "label");
		this.col = col;
		this.row = row;
		this.handler = Objects.requireNonNull(handler, "handler");
		this.icon = icon;
		this.iconU = iconU;
		this.iconV = iconV;
		this.cooldownTicks = cooldownTicks;
	}

	/** 按钮宽度（网格单元）。 */
	public int widthCells() {
		return widthCells;
	}

	/** 按钮高度（网格单元）。 */
	public int heightCells() {
		return heightCells;
	}

	/** 像素矩形（null = 用网格）。 */
	public GuiRect pixelRect() {
		return pixelRect;
	}

	/** 是否为像素级布局。 */
	public boolean usesPixels() {
		return pixelRect != null;
	}

	/** 像素 x（面板相对）。 */
	public int pixelX() {
		return pixelRect != null ? pixelRect.x() : col * ChasmMenu.CELL;
	}

	/** 像素 y（面板相对）。 */
	public int pixelY() {
		return pixelRect != null ? pixelRect.y() : row * ChasmMenu.CELL;
	}

	/** 像素宽。 */
	public int pixelWidth() {
		return pixelRect != null ? pixelRect.width() : widthCells * ChasmMenu.CELL;
	}

	/** 像素高。 */
	public int pixelHeight() {
		return pixelRect != null ? pixelRect.height() : heightCells * ChasmMenu.CELL;
	}

	/**
	 * 便捷构造器：无图标、无按钮级冷却。
	 *
	 * @param label   按钮文字
	 * @param col     所在列
	 * @param row     所在行
	 * @param handler 点击回调
	 */
	public ChasmButton(String label, int col, int row, int widthCells, int heightCells, ChasmButtonHandler handler) {
		this(label, col, row, widthCells, heightCells, handler, null, 0, 0, 0);
	}

	/** 便捷：指定宽度（高度 1 格）。 */
	public ChasmButton(String label, int col, int row, int widthCells, ChasmButtonHandler handler) {
		this(label, col, row, widthCells, 1, handler, null, 0, 0, 0);
	}

	/** 便捷：按文字长度自动算宽度（高度 1 格）。 */
	public static ChasmButton autoWidth(String label, int col, int row, ChasmButtonHandler handler) {
		return new ChasmButton(label, col, row, ChasmGuiText.cells(label), 1, handler);
	}

	public ChasmButton(String label, int col, int row, ChasmButtonHandler handler) {
		this(label, col, row, handler, null, 0, 0, 0);
	}

	/** 按钮文字。 */
	public String label() {
		return label;
	}

	/** 所在列（0 起始）。 */
	public int col() {
		return col;
	}

	/** 所在行（0 起始）。 */
	public int row() {
		return row;
	}

	/** 点击回调（服务端主线程）。 */
	public ChasmButtonHandler handler() {
		return handler;
	}

	/** 图标贴图；null 表示纯文字按钮。 */
	public ResourceLocation icon() {
		return icon;
	}

	/** 图标贴图正常帧左上角 U 坐标。 */
	public int iconU() {
		return iconU;
	}

	/** 图标贴图正常帧左上角 V 坐标。 */
	public int iconV() {
		return iconV;
	}

	/** 该按钮点击冷却（tick 数）；0 表示沿用服务端全局下限。 */
	public int cooldownTicks() {
		return cooldownTicks;
	}
}