package api.chasm.gui;

import java.util.List;

/**
 * 键盘焦点轮转（纯逻辑，可无客户端单测）。
 *
 * <p>规则：只遍历"可聚焦"控件（{@link ChasmWidgetKind#focusable()}），Tab 向后 / Shift+Tab 向前，
 * 首尾循环；没有可聚焦控件时返回 -1（此时界面把按键交还原版，不抢占 Tab）。</p>
 */
public final class ChasmWidgetFocus {

	private ChasmWidgetFocus() {
	}

	/**
	 * 计算下一个焦点索引。
	 *
	 * @param focusableIndexes 可聚焦控件的索引列表（升序）
	 * @param current          当前焦点索引（-1 = 未聚焦）
	 * @param backwards        true = Shift+Tab 反向
	 * @return 新的焦点索引；无可聚焦控件时返回 -1
	 */
	public static int next(List<Integer> focusableIndexes, int current, boolean backwards) {
		if (focusableIndexes == null || focusableIndexes.isEmpty()) {
			return -1;
		}
		int size = focusableIndexes.size();
		int pos = focusableIndexes.indexOf(current);
		if (pos < 0) {
			return backwards ? focusableIndexes.get(size - 1) : focusableIndexes.get(0);
		}
		int step = backwards ? -1 : 1;
		return focusableIndexes.get(Math.floorMod(pos + step, size));
	}

	/** 清空焦点时用：返回 -1。 */
	public static int none() {
		return -1;
	}
}
