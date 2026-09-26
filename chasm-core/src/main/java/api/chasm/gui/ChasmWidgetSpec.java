package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * 控件声明（服务端权威的"数据 + 布局"描述，纯数据、可单测）。
 *
 * <p>布局沿用界面既有的**网格约定**：{@code col/row} 与 {@code widthCells/heightCells}，
 * 每格 {@value ChasmMenu#CELL}px，与服务端槽位坐标一致。</p>
 *
 * <p>值域 {@code [min, max]} 同时承担三件事：①滑块的可选范围；②服务端对客户端上报值的**夹取**
 * （永远不信任客户端）；③进度条 {@code (value - min) / (max - min)} 的比例。</p>
 *
 * @param kind        控件种类（见 {@link ChasmWidgetKinds}）
 * @param key         绑定键：整数数据键（见 {@code ChasmGuiBuilder.data}）或自定义语义键
 * @param label       显示名（可直接给字面量或翻译键）
 * @param col/row     网格位置
 * @param widthCells/heightCells 占用格数（≥1）
 * @param min/max     值域（含端点）
 * @param initial     客户端未收到同步值前的初值
 * @param tooltipKey  悬停提示（翻译键，可 null）
 * @param narrationKey 无障碍朗读文本（翻译键，可 null = 用 label）
 */
public record ChasmWidgetSpec(ResourceLocation kind, String key, String label,
							  int col, int row, int widthCells, int heightCells,
							  int min, int max, int initial,
							  String tooltipKey, String narrationKey, boolean pixel) {

	/**
	 * 兼容构造器：默认**网格坐标**（1 格 = {@value ChasmMenu#CELL}px）。
	 */
	public ChasmWidgetSpec(ResourceLocation kind, String key, String label,
						   int col, int row, int widthCells, int heightCells,
						   int min, int max, int initial,
						   String tooltipKey, String narrationKey) {
		this(kind, key, label, col, row, widthCells, heightCells, min, max, initial,
			tooltipKey, narrationKey, false);
	}

	public ChasmWidgetSpec {
		Objects.requireNonNull(kind, "kind");
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("控件键不可为空");
		}
		Objects.requireNonNull(label, "label");
		if (col < 0 || row < 0) {
			throw new IllegalArgumentException("控件位置不可为负: (" + col + "," + row + ")");
		}
		if (widthCells < 1 || heightCells < 1) {
			throw new IllegalArgumentException("控件尺寸至少 1 格: " + widthCells + "x" + heightCells);
		}
		if (min > max) {
			throw new IllegalArgumentException("控件 " + key + " 的 min(" + min + ") > max(" + max + ")");
		}
		if (initial < min || initial > max) {
			throw new IllegalArgumentException("控件 " + key + " 初值 " + initial + " 不在 [" + min + ", " + max + "] 内");
		}
	}

	/** 夹取到合法值域（服务端处理客户端上报值时必须调用）。 */
	public int clamp(int value) {
		return Math.max(min, Math.min(max, value));
	}

	/** 当前值是否可用（值域非空）。 */
	public boolean hasRange() {
		return max > min;
	}

	/** 由 0~1 比例换算成值（滑块像素 → 值，供客户端与服务端共用，避免两端算法不一致）。 */
	public int valueAt(double fraction) {
		double clamped = Math.max(0.0, Math.min(1.0, fraction));
		return clamp((int) Math.round(min + clamped * (max - min)));
	}

	/** 值 → 0~1 比例（渲染用）。 */
	public double fractionOf(int value) {
		if (!hasRange()) {
			return 0.0;
		}
		return (clamp(value) - (double) min) / (double) (max - min);
	}

	/**
	 * 像素 x（相对界面面板左上角）。
	 *
	 * <p><b>统一坐标空间</b>：像素控件就是面板像素；网格控件按网格原点换算成面板像素。
	 * 两者**同一套结果**，调用方（渲染/命中/比例换算）直接 {@code leftPos + pixelX()}，
	 * 一句 {@code + 8 / + 18} 都不许再写 —— 以前那些字面量就是"控件与槽位差 8/18 像素"的根源。</p>
	 */
	public int pixelX() {
		return pixel ? col : ChasmMenu.GRID_X + col * ChasmMenu.CELL;
	}

	/** 像素 y（相对界面面板左上角；网格控件按 {@link ChasmMenu#GRID_Y} 换算）。 */
	public int pixelY() {
		return pixel ? row : ChasmMenu.GRID_Y + row * ChasmMenu.CELL;
	}

	/** 像素宽。 */
	public int pixelWidth() {
		return pixel ? widthCells : widthCells * ChasmMenu.CELL;
	}

	/** 像素高。 */
	public int pixelHeight() {
		return pixel ? heightCells : heightCells * ChasmMenu.CELL;
	}

	/** 是否在给定像素点内（相对面板左上角；供客户端命中测试，纯函数可测）。 */
	public boolean contains(double relX, double relY) {
		return relX >= pixelX() && relX < pixelX() + pixelWidth()
			&& relY >= pixelY() && relY < pixelY() + pixelHeight();
	}

	/** 无障碍朗读文本（未声明时用 label）。 */
	public String narrationOrDefault() {
		return narrationKey == null || narrationKey.isBlank() ? label : narrationKey;
	}
}