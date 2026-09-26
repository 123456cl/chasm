package api.chasm.gui;

/**
 * 界面文字宽度估算（**服务端安全**，不依赖客户端字体）。
 *
 * <p>为什么需要：按钮/标签的宽度必须在**服务端声明期**就定下来（客户端与服务端共享同一份布局），
 * 而文字实际宽度只有客户端字体知道。这里用字符类别估算像素宽 —— 宁可宽一点（留白）也不要窄
 * （窄了文字就会溢出、重叠，这是实际踩过的坑）。</p>
 *
 * <p>估算规则：CJK/全角字符按 9px，其余按 6px，再加内边距。单元格默认 18px。</p>
 */
public final class ChasmGuiText {

	/** CJK/全角起始码位（中文、日文、韩文、全角标点）。 */
	private static final int CJK_START = 0x2E80;

	/** CJK 字符估算宽度（像素，原版字体约 9px）。 */
	public static final int CJK_CHAR_PX = 9;

	/** 其它字符估算宽度（像素，拉丁小写约 6px）。 */
	public static final int LATIN_CHAR_PX = 6;

	/** 文字左右内边距合计（像素）。 */
	public static final int PADDING_PX = 6;

	private ChasmGuiText() {
	}

	/** 估算字符串像素宽。 */
	public static int pixels(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int px = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			px += cp >= CJK_START ? CJK_CHAR_PX : LATIN_CHAR_PX;
		}
		return px;
	}

	/** 估算文字需要几个网格单元（含内边距，至少 1 格）。 */
	public static int cells(String text) {
		return Math.max(1, (pixels(text) + PADDING_PX + ChasmMenu.CELL - 1) / ChasmMenu.CELL);
	}
}
