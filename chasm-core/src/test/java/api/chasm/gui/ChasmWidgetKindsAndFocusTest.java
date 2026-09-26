package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控件种类开放注册 + 键盘焦点轮转（纯逻辑）。 */
class ChasmWidgetKindsAndFocusTest {

	@Test
	void builtinKindsHaveExpectedMetadata() {
		assertTrue(ChasmWidgetKinds.get(ChasmWidgetKinds.SLIDER.id()).interactive());
		assertTrue(ChasmWidgetKinds.get(ChasmWidgetKinds.SLIDER.id()).focusable());
		assertTrue(ChasmWidgetKinds.get(ChasmWidgetKinds.TOGGLE.id()).interactive());
		assertFalse(ChasmWidgetKinds.get(ChasmWidgetKinds.BAR.id()).interactive(), "进度条是只读的");
		assertFalse(ChasmWidgetKinds.get(ChasmWidgetKinds.BAR.id()).focusable(), "只读控件不参与焦点");
		assertTrue(ChasmWidgetKinds.size() >= 4);
	}

	@Test
	void thirdPartyKindsCanBeRegistered() {
		ResourceLocation tank = ResourceLocation.fromNamespaceAndPath("chasmtest", "tank");
		assertNull(ChasmWidgetKinds.get(tank));
		ChasmWidgetKinds.register(ChasmWidgetKind.display(tank));
		assertNotNull(ChasmWidgetKinds.get(tank));
		assertTrue(ChasmWidgetKinds.isRegistered(tank), "第三方控件种类可自行注册（框架无需改动）");
		assertFalse(ChasmWidgetKinds.get(tank).interactive());
		assertThrows(IllegalArgumentException.class, () -> ChasmWidgetKinds.register(null));
		assertThrows(NullPointerException.class, () -> new ChasmWidgetKind(null, true, true));
	}

	@Test
	void focusCyclesForwardWithWrap() {
		List<Integer> focusable = List.of(0, 2, 5);
		assertEquals(0, ChasmWidgetFocus.next(focusable, -1, false), "无焦点时从第一个开始");
		assertEquals(2, ChasmWidgetFocus.next(focusable, 0, false));
		assertEquals(5, ChasmWidgetFocus.next(focusable, 2, false));
		assertEquals(0, ChasmWidgetFocus.next(focusable, 5, false), "末尾回到开头");
	}

	@Test
	void focusCyclesBackwardsWithWrap() {
		List<Integer> focusable = List.of(0, 2, 5);
		assertEquals(5, ChasmWidgetFocus.next(focusable, -1, true), "无焦点反向时从最后一个开始");
		assertEquals(0, ChasmWidgetFocus.next(focusable, 2, true));
		assertEquals(5, ChasmWidgetFocus.next(focusable, 0, true), "开头回绕到末尾");
	}

	@Test
	void focusHandlesStaleIndexAndEmptyList() {
		assertEquals(0, ChasmWidgetFocus.next(List.of(0, 2, 5), 3, false), "焦点索引失效时重置到首个");
		assertEquals(5, ChasmWidgetFocus.next(List.of(0, 2, 5), 3, true));
		assertEquals(-1, ChasmWidgetFocus.next(List.of(), 0, false), "无可聚焦控件时返回 -1（把按键交还原版）");
		assertEquals(-1, ChasmWidgetFocus.next(null, 0, false));
		assertEquals(-1, ChasmWidgetFocus.none());
	}
}
