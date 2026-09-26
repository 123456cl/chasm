package api.chasm.gui;

import api.chasm.gui.decl.GuiNode;
import api.chasm.gui.decl.client.DeclClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **悬停透明度**（core 新能力）：节点声明"鼠标在矩形内时用另一个 opacity"，
 * 在**渲染时**按当帧指针位置求值。
 *
 * <p>真机出处（逐条）：</p>
 * <pre>
 * opacity 初值 0      VerticalTabButtonGui.java:46(label) / :53(keybing)
 * 悬停淡入           VerticalTabButtonGui.java:48,54 的 labelShow/keybindShow（KeyframeAnimation 到 1）
 * onFocus/onBlur    VerticalTabButtonGui.java:95-104 / :106-116（鼠标进入/移出按钮命中区）
 * 悬停区 = 按钮本体   VerticalTabButtonGui.java:33 的 super(x, y, 0, 15, ...) + :60 的 width
 * </pre>
 *
 * <p>本测试同时钉住"向后兼容"：不调用 {@code opacityWhenHovered(...)} 的节点，
 * 其可观察行为（{@code opacity()} / 透明度乘数）一个字都没变。</p>
 */
class GuiNodeHoverOpacityTest {

	/** 类加载时的默认指针状态（= 读真实鼠标），每个用例后还原，避免污染同 JVM 的其它测试。 */
	private static final DeclClient.PointerState DEFAULT_POINTER_STATE = DeclClient.pointerState;

	@AfterEach
	void restorePointerState() {
		DeclClient.pointerState = DEFAULT_POINTER_STATE;
	}

	/** 只有 {@code opacity(x)} 的节点：任何指针位置下透明度乘数都不变。 */
	@Test
	void plainOpacityIsUnchangedByTheNewCapability() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10).opacity(0.4F);
		List<GuiNode> nodes = List.of(node);

		assertFalse(node.hasHoverOpacity(), "没有声明悬停透明度");
		assertNull(node.hoverRegionKey());
		assertEquals(0.4F, node.opacityAt(false), 1e-6);
		assertEquals(0.4F, node.opacityAt(true), 1e-6, "没声明 → 悬不悬停都是基态");
		assertEquals(0.4, DeclClient.alphaMultiplier("k", node), 1e-6);
		assertEquals(0.4, DeclClient.alphaMultiplier("k", node, true), 1e-6);
		// hoveredAt 只是几何判定（渲染器只对声明过 hoverOpacity 的节点调用它）；
		// 但即使几何命中，opacityAt 也不会改变 —— 这就是"没声明的节点行为逐字不变"。
		assertTrue(DeclClient.hoveredAt(node, nodes, null, 0, 0, 5, 5));
		assertEquals(0.4F, node.opacityAt(true), 1e-6);
	}

	/** 悬停声明只记住"值"，本帧取哪个值由渲染时现算 —— 同一个节点对象两次求值可以不同。 */
	@Test
	void hoverOpacityIsEvaluatedAtRenderTimeNotAtBuildTime() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10)
			.opacity(0.0F)
			.opacityWhenHovered(1.0F);

		assertTrue(node.hasHoverOpacity());
		assertEquals(1.0F, node.hoverOpacity(), 1e-6);
		// 声明后节点自己仍只存"两个声明值"，没有"当前是否悬停"这种隐藏状态
		assertEquals(0.0F, node.opacity(), 1e-6, "基态 opacity 未被改写");
		assertEquals(0.0F, node.opacityAt(false), 1e-6);
		assertEquals(1.0F, node.opacityAt(true), 1e-6);

		// 与 DeclClient 渲染路径同一表达式
		assertEquals(0.0, DeclClient.alphaMultiplier("k", node, false), 1e-9);
		assertEquals(1.0, DeclClient.alphaMultiplier("k", node, true), 1e-9);
	}

	/** 悬停区域可以指向另一个节点（真机：悬停按钮本体 → label 子元素淡入）。 */
	@Test
	void hoverRegionCanReferenceAnotherNodeRect() {
		GuiNode hit = GuiNode.of("tab:hit:0", 10, 20, 40, 15);
		GuiNode label = GuiNode.of("tab:hit:0/1", 100, 100, 18, 9)
			.opacity(0.0F)
			.opacityWhenHovered(1.0F, "tab:hit:0");
		List<GuiNode> nodes = List.of(hit, label);

		assertEquals("tab:hit:0", label.hoverRegionKey());
		// 指针在命中区里、不在 label 自己身上 → 悬停成立（这与"自己的矩形"判定不同）
		assertFalse(DeclClient.hovered(label, nodes, null, 0, 0,
			new DeclClient.Pointer(20, 25, false)), "哨兵指针不在任何节点上");
		assertTrue(DeclClient.hovered(label, nodes, null, 0, 0,
			new DeclClient.Pointer(20, 25, true)), "指针落在悬停区节点内");
		assertFalse(DeclClient.hovered(label, nodes, null, 0, 0,
			new DeclClient.Pointer(105, 105, true)), "指针落在 label 自己的矩形内 ≠ 悬停区");
	}

	/** 坐标换算只有"减面板原点"一处：屏幕像素 → 面板像素。 */
	@Test
	void hoverUsesPanelPixelSpaceNotScreenSpace() {
		GuiNode hit = GuiNode.of("hit", 10, 10, 20, 15);
		GuiNode label = GuiNode.of("label", 0, 0, 5, 5)
			.opacity(0.0F)
			.opacityWhenHovered(1.0F, "hit");
		List<GuiNode> nodes = List.of(hit, label);

		// 面板原点 (50,30)：屏幕 (60,40) = 面板 (10,10) → 命中
		assertTrue(DeclClient.hoveredAt(label, nodes, null, 50, 30, 60, 40));
		// 同一屏幕点换成不同面板原点 → 面板坐标不同 → 不命中
		assertFalse(DeclClient.hoveredAt(label, nodes, null, 0, 0, 60, 40));
		// 命中区右/下边界半开（与 GuiRect.contains 一致）
		assertTrue(DeclClient.hoveredAt(label, nodes, null, 0, 0, 29, 24));
		assertFalse(DeclClient.hoveredAt(label, nodes, null, 0, 0, 30, 25));
	}

	/** 注入指针状态（默认读真实窗口；离线注入假的就能断言，不需要开窗口）。 */
	@Test
	void pointerStateIsInjectableForOfflineAssertions() {
		GuiNode hit = GuiNode.of("hit", 10, 10, 20, 15);
		GuiNode label = GuiNode.of("label", 0, 0, 5, 5)
			.opacity(0.0F)
			.opacityWhenHovered(1.0F, "hit");
		List<GuiNode> nodes = List.of(hit, label);

		DeclClient.pointerState = () -> new DeclClient.Pointer(15, 15, true);
		assertTrue(DeclClient.hovered(label, nodes, null, 0, 0));
		assertEquals(1.0, DeclClient.alphaMultiplier("k", label,
			DeclClient.hovered(label, nodes, null, 0, 0)), 1e-9);

		DeclClient.pointerState = () -> new DeclClient.Pointer(200, 200, true);
		assertFalse(DeclClient.hovered(label, nodes, null, 0, 0));
		assertEquals(0.0, DeclClient.alphaMultiplier("k", label,
			DeclClient.hovered(label, nodes, null, 0, 0)), 1e-9);

		DeclClient.pointerState = () -> DeclClient.Pointer.NOWHERE;
		assertFalse(DeclClient.hovered(label, nodes, null, 0, 0), "无指针哨兵不落在任何节点上");
	}

	/** 参数校验与夹取：越界 opacity 夹到 0..1；空区域 key 在声明处就报错。 */
	@Test
	void invalidDeclarationsAreRejectedEagerly() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10);
		assertThrows(IllegalArgumentException.class, () -> node.opacityWhenHovered(1.0F, null));
		assertThrows(IllegalArgumentException.class, () -> node.opacityWhenHovered(1.0F, ""));

		assertEquals(1.0F, node.opacityWhenHovered(5.0F).hoverOpacity(), 1e-6, "上界夹取");
		assertEquals(0.0F, node.opacityWhenHovered(-3.0F).hoverOpacity(), 1e-6, "下界夹取");
	}

	/** 悬停区 key 在本帧列表里不存在 → 回落到本节点自己的矩形（不崩、不误判）。 */
	@Test
	void missingHoverRegionFallsBackToOwnRect() {
		GuiNode label = GuiNode.of("label", 10, 10, 20, 15)
			.opacity(0.0F)
			.opacityWhenHovered(1.0F, "not-in-this-list");
		List<GuiNode> nodes = List.of(label);

		assertFalse(DeclClient.hoveredAt(label, nodes, null, 0, 0, 0, 0));
		assertTrue(DeclClient.hoveredAt(label, nodes, null, 0, 0, 15, 15));
	}
}
