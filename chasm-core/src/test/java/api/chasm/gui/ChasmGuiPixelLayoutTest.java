package api.chasm.gui;

import api.chasm.Chasm;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自由尺寸 + 像素级布局（移植别人固定像素 UI 的根基）：
 * 面板尺寸、像素槽位、像素按钮、玩家物品栏位置，以及像素矩形的重叠判定。
 */
class ChasmGuiPixelLayoutTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void pixelSlotAndButtonKeepExactCoordinates() {
		// 神化重铸台的真实坐标（抄自 ReforgingMenu/ReforgingScreen）
		ChasmGui gui = Chasm.gui("chasmtest", "reforge_like")
			.panelSize(176, 266)
			.playerInventoryAt(8, 184)
			.slotPx(81, 62, stack -> stack.getMaxStackSize() == 1)
			.slotPx(39, 40)
			.buttonPx("普", 27, 135, 16, 16, (p, c) -> { })
			.buttonPx("稀", 81, 135, 16, 16, (p, c) -> { })
			.register();

		assertEquals(176, gui.panelWidth(), "固定面板尺寸必须原样保留（不能按网格推导）");
		assertEquals(266, gui.panelHeight());
		assertEquals(8, gui.playerInvX(), "玩家物品栏位置可指定（神化是 8,184）");
		assertEquals(184, gui.playerInvY());
		assertEquals(81, gui.storageSlots().get(0).pixelX(), "像素槽位必须精确在 (81,62)");
		assertEquals(62, gui.storageSlots().get(0).pixelY());
		assertEquals(39, gui.storageSlots().get(1).pixelX());
		assertTrue(gui.buttons().get(0).usesPixels());
		assertEquals(27, gui.buttons().get(0).pixelX());
		assertEquals(135, gui.buttons().get(0).pixelY());
		assertEquals(16, gui.buttons().get(0).pixelWidth());
	}

	/**
	 * 像素按钮的矩形必须**原样**保留（零偏移），重叠则按"后声明的在上面"处理。
	 *
	 * <p>这两件事一起构成了替换掉 {@code validateLayout()} 的理由：
	 * 坐标系正确之后，重叠不需要被禁止，只需要有确定的层叠顺序。</p>
	 */
	@Test
	void pixelButtonsKeepExactRectsAndStackByOrder() {
		ChasmGui gui = Chasm.gui("chasmtest", "px_overlap").panelSize(176, 266)
			.buttonPx("A", 27, 135, 16, 16, (p, c) -> { })
			.buttonPx("B", 30, 137, 16, 16, (p, c) -> { })
			.register();
		assertEquals(2, gui.buttons().size(), "重叠不再是声明期错误");
		assertEquals(27, gui.buttons().get(0).pixelX());
		assertEquals(135, gui.buttons().get(0).pixelY());
		assertEquals(30, gui.buttons().get(1).pixelX(), "第二个像素按钮的坐标也不许被偏移");
		assertEquals(137, gui.buttons().get(1).pixelY());
	}

	@Test
	void regionValidationRejectsOutOfBounds() {
		assertThrows(IllegalArgumentException.class, () -> new BackgroundRegion(
			net.minecraft.resources.ResourceLocation.parse("apotheosis:textures/gui/reforge.png"),
			256, 384, 200, 0, 176, 266), "区域越出图集必须报错（否则必然取到图外花屏）");
		// 神化真实参数：合法
		new BackgroundRegion(net.minecraft.resources.ResourceLocation.parse(
			"apotheosis:textures/gui/reforge.png"), 256, 384, 0, 0, 176, 266);
	}

	@Test
	void guiRectHitTest() {
		GuiRect r = new GuiRect(10, 20, 16, 16);
		assertTrue(r.contains(10, 20));
		assertTrue(r.contains(25, 35));
		assertFalse(r.contains(26, 35), "右边界开区间");
		assertFalse(r.contains(10, 36), "下边界开区间");
	}
}
