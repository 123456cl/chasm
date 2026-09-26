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
 * **按键条件 tooltip**（core 新能力）：静态基串**逐字不变**，shift 时整份换成展开版。
 *
 * <p>真机出处（逐条）：</p>
 * <pre>
 * 按键分支      GuiStatBar.getTooltipLines:221-229   Screen.hasShiftDown() ? extendedTooltip : tooltip
 * 非 shift 构造  GuiStatBar.getCombinedTooltip:248-260   基串 + (" " + item.tetra.tooltip_expand)
 * shift 构造    GuiStatBar.getCombinedTooltipExtended:262-294
 *               基串 + (" " + item.tetra.tooltip_expanded) + 灰色扩展正文
 * 工具行扩展     TooltipGetterTool.hasExtendedTooltip:69-71 恒 true → :73-75 的 tooltip_extended
 * </pre>
 *
 * <p>本测试同时钉住"向后兼容"：只调用 {@code tooltip(x)} 的节点，其可观察行为一个字都没变。</p>
 */
class GuiNodeKeyedTooltipTest {

	/** 类加载时的默认按键状态（= 读真实键盘），每个用例后还原，避免污染同 JVM 的其它测试。 */
	private static final DeclClient.KeyState DEFAULT_KEY_STATE = DeclClient.keyState;

	@AfterEach
	void restoreKeyState() {
		DeclClient.keyState = DEFAULT_KEY_STATE;
	}

	/** 只有 {@code tooltip(x)} 的节点：任何按键状态下都返回同一个基串。 */
	@Test
	void plainTooltipIsUnchangedByTheNewCapability() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10).tooltip("base");

		assertEquals("base", node.tooltip(), "tooltip() 仍是那个静态基串");
		assertFalse(node.hasKeyedTooltips(), "没有声明按键条件");
		assertEquals(List.of(), node.keyedTooltips());
		assertEquals("base", node.tooltipAt(key -> true), "任何键按下都还是基串");
		assertEquals("base", node.tooltipAt(key -> false));
		assertEquals("base", node.tooltipAt(null), "null 判定 = 没有键按下");
	}

	/** shift 按下 → 整份换成展开版；松开 → 回到基串（真机 :223-226 的两个返回分支）。 */
	@Test
	void shiftKeySwapsTheWholeTooltip() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10)
			.tooltip("base")
			.tooltipWhen(GuiNode.SHIFT_KEY, "expanded");

		assertEquals("base", node.tooltip(), "基串不受影响");
		assertTrue(node.hasKeyedTooltips());
		assertEquals(List.of(new GuiNode.KeyedTooltip("shift", "expanded")), node.keyedTooltips());
		assertEquals("expanded", node.tooltipAt("shift"::equals), "shift 按下 → 展开版");
		assertEquals("base", node.tooltipAt(k -> false), "shift 松开 → 基串");
	}

	/** 多个条件按声明顺序匹配，第一个命中的胜出。 */
	@Test
	void firstMatchingKeyWins() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10)
			.tooltip("base")
			.tooltipWhen(GuiNode.SHIFT_KEY, "shift-text")
			.tooltipWhen("ctrl", "ctrl-text");

		assertEquals("shift-text", node.tooltipAt(key -> true), "都按下时先声明的 shift 胜");
		assertEquals("ctrl-text", node.tooltipAt("ctrl"::equals));
		assertEquals("base", node.tooltipAt(k -> false));
		assertEquals(2, node.keyedTooltips().size());
	}

	/** {@link DeclClient} 在渲染时按注入的按键状态求值（不需要开窗口）。 */
	@Test
	void declClientEvaluatesTheKeyAtRenderTime() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10)
			.tooltip("base")
			.tooltipWhen(GuiNode.SHIFT_KEY, "expanded");

		DeclClient.keyState = key -> GuiNode.SHIFT_KEY.equals(key);
		assertEquals("expanded", DeclClient.tooltipText(node));

		DeclClient.keyState = key -> false;
		assertEquals("base", DeclClient.tooltipText(node));

		// 同一节点、同一帧内状态变化即刻反映 —— 说明没有把结果缓存进节点
		DeclClient.keyState = key -> true;
		assertEquals("expanded", DeclClient.tooltipText(node));

		assertNull(DeclClient.tooltipText(null));
	}

	/** 参数校验：空键 / null 文本必须在声明处就报错，而不是渲染期才炸。 */
	@Test
	void invalidDeclarationsAreRejectedEagerly() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10);
		assertThrows(IllegalArgumentException.class, () -> node.tooltipWhen(null, "x"));
		assertThrows(IllegalArgumentException.class, () -> node.tooltipWhen("", "x"));
		assertThrows(IllegalArgumentException.class, () -> node.tooltipWhen(GuiNode.SHIFT_KEY, null));
	}

	/** 只声明按键条件、不声明基串：没有键按下时确实是 null（= 不弹 tooltip），不编造文本。 */
	@Test
	void keyedOnlyNodeHasNoBaseTooltip() {
		GuiNode node = GuiNode.of("n", 0, 0, 10, 10).tooltipWhen(GuiNode.SHIFT_KEY, "expanded");
		assertEquals("expanded", node.tooltipAt(key -> true));
		assertNull(node.tooltipAt(key -> false));
	}
}
