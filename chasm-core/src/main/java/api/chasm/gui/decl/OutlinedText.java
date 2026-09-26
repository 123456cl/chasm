package api.chasm.gui.decl;

import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * **8 向描边文字**：真实 mutil 的 {@code GuiStringOutline} 的逐字复刻（无 mixin、纯声明式）。
 *
 * <p>真实实现 {@code _ref/Mutil-1.20/mutil-1.20/src/main/java/se/mickelus/mutil/gui/GuiStringOutline.java:52-70}
 * 只做三件事，本类逐条照抄：</p>
 * <ol>
 *   <li><b>先 strip 格式码</b>（{@code cleanString = ChatFormatting.stripFormatting(string)}，:13）：8 次描边
 *       画的是 {@code cleanString}，正文那一次画的仍是原串（:55-64 用 {@code cleanString}、:68 用 {@code text}）。</li>
 *   <li><b>用黑色把正文画 8 次</b>，偏移与顺序逐行相同（:55-64）：
 *       {@code (-1,-1) (0,-1) (+1,-1) (-1,+1) (0,+1) (+1,+1) (+1,0) (-1,0)}。
 *       黑色是字面量 {@code 0}，经 mutil 的 {@code GuiElement.colorWithOpacity:382-385} 补 alpha
 *       （{@code color & 0xffffff | (opacity * (alpha == 0 ? 255 : alpha) / 255 << 24)}）→ 不透明黑
 *       {@link #OUTLINE_COLOR}。</li>
 *   <li><b>正文再画一次本色</b>，但 pose 先沿 z 平移
 *       {@code 0.0020000000949949026D}（真实注释：magic offset to avoid z-fighting for in-world rendering，
 *       :66-68）—— 不抬 z 就会与 8 次描边 z-fighting，边缘出现闪烁的锯齿。</li>
 * </ol>
 *
 * <p>三次绘制**都没有阴影**（构造函数里的 {@code drawShadow = false}，:11）。</p>
 *
 * <h2>为什么偏移要放在这个类里、而不是散在渲染代码里</h2>
 * <p>偏移表是"外观契约"（少一个方向就少一条边、顺序无关但数量有关），渲染代码在客户端、
 * 单测里开不了窗口。把"这一帧要发哪几次 {@code drawString}、每次什么偏移/颜色/z"抽成
 * 纯数据（{@link #passes}），客户端只是照单执行，测试则能逐条断言 —— 与
 * {@link api.chasm.gui.decl.GuiNode} 用数据描述界面是同一个思路。</p>
 */
public final class OutlinedText {

	/**
	 * 描边色：真实写的是字面量 {@code 0}（{@code GuiStringOutline.java:55-64}），
	 * 经 {@code GuiElement.colorWithOpacity} 补上不透明 alpha 后就是纯黑。
	 */
	public static final int OUTLINE_COLOR = 0xFF000000;

	/** 描边方向数（真实连续 8 次 {@code drawString}）。 */
	public static final int OFFSET_COUNT = 8;

	/** 8 个方向的 x 偏移，顺序 = 真实第 55、56、57、59、60、61、63、64 行的调用顺序。 */
	private static final int[] OFFSET_X = { -1, 0, 1, -1, 0, 1, 1, -1 };
	/** 8 个方向的 y 偏移（与 {@link #OFFSET_X} 一一对应）。 */
	private static final int[] OFFSET_Y = { -1, -1, -1, 1, 1, 1, 0, 0 };

	/** 正文的 z 平移量（真实 {@code GuiStringOutline.java:67} 的魔数，逐位相同）。 */
	public static final double BODY_Z = 0.0020000000949949026D;

	private OutlinedText() {
	}

	/** 第 {@code pass} 次描边的 x 偏移（{@code pass} ∈ [0, {@link #OFFSET_COUNT})）。 */
	public static int offsetX(int pass) {
		return OFFSET_X[pass];
	}

	/** 第 {@code pass} 次描边的 y 偏移。 */
	public static int offsetY(int pass) {
		return OFFSET_Y[pass];
	}

	/** 去掉 {@code §} 格式码（真实 {@code ChatFormatting.stripFormatting}）。null 进 null 出。 */
	public static String clean(String text) {
		return text == null ? null : ChatFormatting.stripFormatting(text);
	}

	/**
	 * **一次文字绘制**。
	 *
	 * @param text   要画的串（描边 8 次画的是 strip 过的串、正文画原串 —— 与真实一致）
	 * @param dx     x 偏移（描边 ±1、正文 0）
	 * @param dy     y 偏移
	 * @param color  已含 alpha 的 ARGB
	 * @param z      pose 的 z 平移
	 * @param shadow 是否带阴影（描边与正文都 false）
	 */
	public record Pass(String text, int dx, int dy, int color, double z, boolean shadow) {
	}

	/**
	 * **这一帧这个文字节点要发的绘制调用**（顺序 = 绘制顺序）。
	 *
	 * <p>普通节点（{@code !node.isTextOutlined()}）恒为 1 次，颜色/阴影/偏移与加本能力之前**逐字相同**
	 * —— 这是向后兼容的落点：{@code text()} 建出来的节点走的还是这条分支。</p>
	 *
	 * <p>描边节点 9 次：先 8 次黑字（{@code shadow=false}，颜色 = {@link #OUTLINE_COLOR} 的 RGB
	 * 套上 {@code bodyColor} 的 alpha，这样淡入/透明度对描边同样生效），再 1 次本色字（{@code z = }{@link #BODY_Z}）。</p>
	 *
	 * @param node      文字节点（{@code node.text() == null} → 空表）
	 * @param bodyColor 正文最终色（**已含 alpha**，与 {@code DeclClient.render} 里算出来的那个一致）
	 */
	public static List<Pass> passes(GuiNode node, int bodyColor) {
		if (node == null || node.text() == null) {
			return List.of();
		}
		if (!node.isTextOutlined()) {
			// 向后兼容：普通文字节点与以前完全一致（1 次绘制、偏移 0、z 0、阴影跟随节点）
			return List.of(new Pass(node.text(), 0, 0, bodyColor, 0.0D, node.textShadow()));
		}
		int alpha = (bodyColor >>> 24) & 0xFF;
		int outline = (alpha << 24) | (OUTLINE_COLOR & 0xFFFFFF);
		String clean = clean(node.text());
		List<Pass> out = new ArrayList<>(OFFSET_COUNT + 1);
		for (int i = 0; i < OFFSET_COUNT; i++) {
			out.add(new Pass(clean, OFFSET_X[i], OFFSET_Y[i], outline, 0.0D, false));
		}
		out.add(new Pass(node.text(), 0, 0, bodyColor, BODY_Z, false));
		return List.copyOf(out);
	}
}
