package api.chasm.gui;

/**
 * 面板相对坐标下的**像素矩形**（本轮新增：自由尺寸布局的基本单位）。
 *
 * <p>为什么需要：网格坐标（col/row × 18px）适合"自己从零设计"的界面，但移植别人的界面时，
 * 对方用的是**固定像素布局**（例：神化重铸台面板 176×266，主物品槽正好在 (81,62)）。
 * 硬套网格只能近似，永远复刻不了。因此每个元素都可以选择"网格"或"像素矩形"。</p>
 *
 * @param x      相对面板左上角的 x
 * @param y      相对面板左上角的 y
 * @param width  宽（像素）
 * @param height 高（像素）
 */
public record GuiRect(int x, int y, int width, int height) {

	public GuiRect {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("矩形尺寸必须为正: " + width + "x" + height);
		}
	}

	/** 标准物品槽尺寸（16×16）。 */
	public static GuiRect slotAt(int x, int y) {
		return new GuiRect(x, y, 16, 16);
	}

	public int right() {
		return x + width;
	}

	public int bottom() {
		return y + height;
	}

	/** 相对坐标是否落在矩形内（界面命中测试用）。 */
	public boolean contains(double relX, double relY) {
		return relX >= x && relX < right() && relY >= y && relY < bottom();
	}
}
