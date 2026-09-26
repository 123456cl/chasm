package api.chasm.gui;

import api.chasm.gui.decl.GuiNode;
import api.chasm.gui.decl.OutlinedText;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **8 向描边文字的逐条契约**（真实 {@code GuiStringOutline.java:52-70} 的离线复刻校验）。
 *
 * <p>渲染代码在客户端、单测里开不了窗口，所以"这一帧发了几次 {@code drawString}、每次什么偏移/颜色/z"
 * 被抽成纯数据 {@link OutlinedText#passes}（{@code DeclClient.render} 只是照单执行）。
 * 这里断言的就是那份数据 —— 方向数少一个、偏移抄错一对、z 没抬，都会在这里挂。</p>
 */
class OutlinedTextTest {

	/** 真实 8 次描边的方向与顺序（{@code GuiStringOutline.java:55-64} 逐行）。 */
	private static final int[][] REAL_OFFSETS = {
		{ -1, -1 }, { 0, -1 }, { 1, -1 },
		{ -1, 1 }, { 0, 1 }, { 1, 1 },
		{ 1, 0 }, { -1, 0 }
	};

	@Test
	void offsetsAreTheRealEightDirectionsInTheRealOrder() {
		assertEquals(8, OutlinedText.OFFSET_COUNT, "真实连续 8 次 drawString");
		for (int i = 0; i < REAL_OFFSETS.length; i++) {
			assertEquals(REAL_OFFSETS[i][0], OutlinedText.offsetX(i),
				"第 " + i + " 次描边的 x 偏移（GuiStringOutline.java:55-64）");
			assertEquals(REAL_OFFSETS[i][1], OutlinedText.offsetY(i),
				"第 " + i + " 次描边的 y 偏移（GuiStringOutline.java:55-64）");
		}
		// 真实写的是字面量 0，经 GuiElement.colorWithOpacity(0, opacity) 补 alpha → 不透明黑（GuiElement.java:382-385）
		assertEquals(0xFF000000, OutlinedText.OUTLINE_COLOR, "描边色 = 纯黑");
		// "magic offset to avoid z-fighting for in-world rendering"（GuiStringOutline.java:67）逐位相同
		assertEquals(0.0020000000949949026D, OutlinedText.BODY_Z, "正文的 z 抬升量");
	}

	@Test
	void formattingCodesAreStrippedForTheOutline() {
		assertEquals("12", OutlinedText.clean("§a12§r"), "ChatFormatting.stripFormatting 的语义");
		assertEquals(null, OutlinedText.clean(null), "null 进 null 出");
	}

	/** **向后兼容的落点**：{@code text()} 建的节点恒为 1 次绘制，偏移/颜色/阴影与加本能力之前一致。 */
	@Test
	void plainTextNodesKeepTheirSingleUnchangedDrawCall() {
		GuiNode node = GuiNode.of("k", 0, 0, 10, 8).text("12", 0xFF55FF55);
		assertFalse(node.isTextOutlined(), "text() 不许隐式变成描边");
		assertTrue(node.textShadow(), "默认阴影仍是 true（mutil GuiString.drawShadow 默认）");

		List<OutlinedText.Pass> passes = OutlinedText.passes(node, 0xFF55FF55);
		assertEquals(1, passes.size(), "普通文字节点 = 1 次绘制（外观逐字不变）");
		OutlinedText.Pass pass = passes.get(0);
		assertEquals("12", pass.text());
		assertEquals(0, pass.dx());
		assertEquals(0, pass.dy());
		assertEquals(0.0D, pass.z());
		assertEquals(0xFF55FF55, pass.color());
		assertTrue(pass.shadow(), "普通文字的阴影跟随节点字段");
	}

	/** 描边节点 = 8 次黑字 + 1 次本色字；9 次全无阴影，正文抬 z。 */
	@Test
	void outlinedTextDrawsEightBlackPassesThenTheColoredBody() {
		GuiNode node = GuiNode.of("k", 0, 0, 10, 8).textOutlined("7", 0xFF55FF55);
		assertTrue(node.isTextOutlined());
		assertFalse(node.textShadow(), "GuiStringOutline 构造时 drawShadow=false（GuiStringOutline.java:11）");

		// 正文色带 alpha（界面淡入/透明度算出来的就是这种）→ 描边必须跟着同一个 alpha
		List<OutlinedText.Pass> passes = OutlinedText.passes(node, 0x8055FF55);
		assertEquals(9, passes.size(), "8 次黑边 + 1 次本色");
		for (int i = 0; i < 8; i++) {
			OutlinedText.Pass pass = passes.get(i);
			assertEquals(0x80000000, pass.color(), "描边 = 黑套上正文 alpha（GuiElement.colorWithOpacity(0,…)）");
			assertFalse(pass.shadow(), "描边无阴影");
			assertEquals(0.0D, pass.z(), "8 次描边都在原 z");
			assertEquals("7", pass.text());
			assertEquals(OutlinedText.offsetX(i), pass.dx());
			assertEquals(OutlinedText.offsetY(i), pass.dy());
		}
		OutlinedText.Pass body = passes.get(8);
		assertEquals("7", body.text());
		assertEquals(0x8055FF55, body.color(), "正文用本色（含 alpha）");
		assertEquals(OutlinedText.BODY_Z, body.z(), "正文抬 z 防 z-fighting");
		assertFalse(body.shadow());
	}

	/** 8 次描边画 strip 过的串，正文仍画原串 —— 与真实 :55-64 / :68 的分工一致。 */
	@Test
	void outlinePassesUseTheCleanStringWhileTheBodyKeepsTheOriginal() {
		GuiNode node = GuiNode.of("k", 0, 0, 10, 8).textOutlined("§a12", 0xFF55FF55);
		List<OutlinedText.Pass> passes = OutlinedText.passes(node, 0xFF55FF55);
		assertEquals(9, passes.size());
		for (int i = 0; i < 8; i++) {
			assertEquals("12", passes.get(i).text(), "描边画 cleanString（GuiStringOutline.java:13,55）");
		}
		assertEquals("§a12", passes.get(8).text(), "正文画原串（:68 用的是 text）");
	}

	@Test
	void outlineCanBeTurnedOffAgainAndEmptyTextHasNoPasses() {
		GuiNode node = GuiNode.of("k", 0, 0, 10, 8).text("1", 0xFF000000).outline(true).outline(false);
		assertFalse(node.isTextOutlined(), "outline(false) 能关掉");
		assertEquals(1, OutlinedText.passes(node, 0xFF000000).size(), "关掉后回到普通文字");

		assertEquals(0, OutlinedText.passes(GuiNode.of("k", 0, 0, 10, 8), 0xFFFFFFFF).size(),
			"没有文字的节点一次都不画");
	}
}
