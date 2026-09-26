package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控件声明：值域夹取、比例换算、命中测试、非法声明早报错。 */
class ChasmWidgetSpecTest {

	private static ChasmWidgetSpec spec(int min, int max, int initial) {
		return new ChasmWidgetSpec(ChasmWidgetKinds.SLIDER.id(), "mana", "法力",
			2, 1, 4, 1, min, max, initial, "mymod.tip.mana", null);
	}

	@Test
	void clampsValuesToDeclaredRange() {
		ChasmWidgetSpec spec = spec(0, 100, 10);
		assertEquals(0, spec.clamp(-5));
		assertEquals(100, spec.clamp(500));
		assertEquals(42, spec.clamp(42));
	}

	@Test
	void fractionAndValueConversionsAreConsistent() {
		ChasmWidgetSpec spec = spec(0, 100, 10);
		assertEquals(50, spec.valueAt(0.5));
		assertEquals(0.5, spec.fractionOf(50), 1.0E-9);
		assertEquals(0, spec.valueAt(-1.0), "比例越界应夹取");
		assertEquals(100, spec.valueAt(2.0));
		// 往返一致：像素比例 → 值 → 比例（客户端与服务端共用同一实现，避免两端算出不同值）
		for (double f = 0.0; f <= 1.0; f += 0.05) {
			int value = spec.valueAt(f);
			assertEquals(spec.clamp(value), value);
			assertTrue(Math.abs(spec.fractionOf(value) - f) < 0.06, "fraction round trip f=" + f);
		}
	}

	@Test
	void degenerateRangeIsSafe() {
		ChasmWidgetSpec fixed = spec(5, 5, 5);
		assertFalse(fixed.hasRange());
		assertEquals(0.0, fixed.fractionOf(5), "值域为空时不得除零");
		assertEquals(5, fixed.valueAt(0.7));
	}

	/**
	 * **统一坐标空间**：网格控件与像素控件都换算成"面板像素"，调用方只加一次面板原点。
	 *
	 * <p>网格的原点由 {@link ChasmMenu#GRID_X}/{@link ChasmMenu#GRID_Y} 定义，
	 * 与存储槽位用的是同一对常量 —— 这就是"控件与槽位不再差 8/18 像素"的根据。</p>
	 */
	@Test
	void hitTestUsesPanelPixelSpace() {
		ChasmWidgetSpec spec = spec(0, 100, 10);   // col=2,row=1,4x1 格，每格 18px
		int x = ChasmMenu.GRID_X + 2 * ChasmMenu.CELL;   // 8 + 36 = 44
		int y = ChasmMenu.GRID_Y + 1 * ChasmMenu.CELL;   // 18 + 18 = 36
		assertEquals(x, spec.pixelX());
		assertEquals(y, spec.pixelY());
		assertEquals(72, spec.pixelWidth());
		assertEquals(18, spec.pixelHeight());
		assertTrue(spec.contains(x, y), "左上角在内");
		assertFalse(spec.contains(x - 1, y), "左侧越界");
		assertTrue(spec.contains(x + 71, y + 17));
		assertFalse(spec.contains(x + 72, y + 17), "右边界为开区间");
		assertFalse(spec.contains(x, y + 18), "下边界为开区间");

		// 同一个网格坐标在槽位上也是同一像素（两端永远对齐）
		ChasmStorageSlot slot = new ChasmStorageSlot(0, 2, 1);
		assertEquals(x, slot.pixelX());
		assertEquals(y, slot.pixelY());

		// 像素控件：坐标**原样**是面板像素，不再被加 8/18（这是"像素 UI 整体偏移"的老病根）
		ChasmWidgetSpec pixelSpec = new ChasmWidgetSpec(ChasmWidgetKinds.BAR.id(), "energy", "能量",
			0, 0, 320, 240, 0, 100, 0, null, null, true);
		assertEquals(0, pixelSpec.pixelX(), "像素控件 (0,0) 就是面板左上角");
		assertEquals(0, pixelSpec.pixelY());
		assertEquals(320, pixelSpec.pixelWidth());
	}

	@Test
	void invalidDeclarationsFailFast() {
		assertThrows(IllegalArgumentException.class,
			() -> new ChasmWidgetSpec(ChasmWidgetKinds.SLIDER.id(), " ", "x", 0, 0, 1, 1, 0, 1, 0, null, null));
		assertThrows(IllegalArgumentException.class,
			() -> new ChasmWidgetSpec(ChasmWidgetKinds.SLIDER.id(), "k", "x", 0, 0, 1, 1, 10, 5, 7, null, null),
			"min>max 应报错");
		assertThrows(IllegalArgumentException.class,
			() -> new ChasmWidgetSpec(ChasmWidgetKinds.SLIDER.id(), "k", "x", 0, 0, 1, 1, 0, 10, 99, null, null),
			"初值越界应报错");
		assertThrows(IllegalArgumentException.class,
			() -> new ChasmWidgetSpec(ChasmWidgetKinds.SLIDER.id(), "k", "x", 0, 0, 0, 1, 0, 10, 5, null, null),
			"尺寸至少 1 格");
		assertThrows(IllegalArgumentException.class,
			() -> new ChasmWidgetSpec(ChasmWidgetKinds.SLIDER.id(), "k", "x", -1, 0, 1, 1, 0, 10, 5, null, null));
		assertThrows(NullPointerException.class,
			() -> new ChasmWidgetSpec(null, "k", "x", 0, 0, 1, 1, 0, 10, 5, null, null));
	}

	@Test
	void narrationFallsBackToLabel() {
		assertEquals("法力", spec(0, 100, 10).narrationOrDefault());
		ChasmWidgetSpec withKey = new ChasmWidgetSpec(ChasmWidgetKinds.SLIDER.id(), "k", "法力",
			0, 0, 1, 1, 0, 10, 5, null, "mymod.narrate.k");
		assertEquals("mymod.narrate.k", withKey.narrationOrDefault());
	}
}
