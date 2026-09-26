package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * 控件种类（**开放注册**）：第三方模组可以注册自己的控件种类（如液体槽、滚动列表、标签页）。
 *
 * <p>服务端只认"种类 id + 交互性 + 可聚焦性"这三件事，具体长什么样、怎么被操作，由客户端渲染器
 * （{@code ChasmWidgetRenderers}）决定 —— 因此**加一种控件不需要改框架**。</p>
 *
 * @param id         种类 id（如 {@code chasm:slider}）
 * @param interactive 是否允许客户端发起动作（服务端会据此拒绝只读控件的包）
 * @param focusable  是否参与键盘焦点（Tab 轮转 / 方向键调整）
 */
public record ChasmWidgetKind(ResourceLocation id, boolean interactive, boolean focusable, ClickAxis clickAxis) {

	/**
	 * **点击如何换算成值** —— 这是"列表 / 图表 / 选项卡"这类控件的关键差异。
	 *
	 * <ul>
	 *   <li>{@link #X_FRACTION}：按鼠标**横向**比例（滑块、进度条）；</li>
	 *   <li>{@link #Y_ROW}：按鼠标**纵向**比例换算成**行号**（列表、背包格子、选项卡）—— 列表就靠它；</li>
	 *   <li>{@link #CLICK}：只发一个"被点了"（值恒为 1）。</li>
	 * </ul>
	 */
	public enum ClickAxis {
		X_FRACTION,
		Y_ROW,
		CLICK,
		/**
		 * **声明式节点**：值 = 客户端在节点列表里命中到的**节点序号**。
		 * 服务端拿到序号后重建同一份节点列表再派发 —— 客户端不传坐标，永远不会错位。
		 */
		NODE
	}

	/** 兼容构造器：默认按横向比例（滑块语义）。 */
	public ChasmWidgetKind(ResourceLocation id, boolean interactive, boolean focusable) {
		this(id, interactive, focusable, ClickAxis.X_FRACTION);
	}

	public ChasmWidgetKind {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(clickAxis, "clickAxis");
	}

	/** 便捷：可交互且可聚焦。 */
	public static ChasmWidgetKind interactive(ResourceLocation id) {
		return new ChasmWidgetKind(id, true, true);
	}

	/** 便捷：只读展示（数据由服务端推送，客户端不可操作）。 */
	public static ChasmWidgetKind display(ResourceLocation id) {
		return new ChasmWidgetKind(id, false, false, ClickAxis.CLICK);
	}

	/**
	 * 便捷：**列表控件**（可交互、按行命中）。
	 *
	 * <p>值域应当写成 {@code 0..行数-1}：点击第 N 行 → 服务端收到值 N。</p>
	 */
	public static ChasmWidgetKind list(ResourceLocation id) {
		return new ChasmWidgetKind(id, true, true, ClickAxis.Y_ROW);
	}
}