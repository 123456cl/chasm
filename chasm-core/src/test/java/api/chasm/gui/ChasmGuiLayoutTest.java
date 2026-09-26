package api.chasm.gui;

import api.chasm.Chasm;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI 可用性三件套（来自实际踩坑）：
 * ①按钮宽度要按文字长度算；②按钮/控件不得重叠、不得超出网格；③槽位要能过滤物品。
 */
class ChasmGuiLayoutTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void textWidthEstimateIsNeverTooSmall() {
		assertEquals(0, ChasmGuiText.pixels(""));
		assertTrue(ChasmGuiText.cells("标记·已退役") >= 4, "长标签至少要 4 格宽，否则必然溢出重叠");
		assertEquals(1, ChasmGuiText.cells("A"), "再短也至少 1 格");
		assertTrue(ChasmGuiText.pixels("中文") > ChasmGuiText.pixels("ab"), "CJK 估算宽于拉丁");
	}

	/**
	 * **重叠不再被拒绝**（范式变更）：顺序就是 z 序，重叠是"谁压住谁"而不是错误。
	 *
	 * <p>旧实现有一百多行 {@code validateLayout()} 专门抛"重叠"异常，代价是每加一种元素都要给
	 * 校验器打补丁（像素控件那次把 320px 当成 320 格的误报就是这么来的），而且真正想叠一层
	 * 高亮时反而被拦住。声明式范式的答案不是"禁止重叠"，而是"重叠时谁在上面"。</p>
	 */
	@Test
	void overlappingButtonsAreAllowedBecauseOrderIsZOrder() {
		ChasmGui gui = Chasm.gui("chasmtest", "overlap").size(9, 3)
			.button("长按钮文字", 2, 0, 4, (p, c) -> { })
			.button("另一个按钮", 4, 0, 4, (p, c) -> { })
			.register();
		assertEquals(2, gui.buttons().size(), "重叠只是层叠关系，两个按钮都在");
	}

	@Test
	void buttonsBeyondGridAreRejected() {
		assertThrows(IllegalArgumentException.class, () ->
			Chasm.gui("chasmtest", "toowide").size(6, 3)
				.button("太长的按钮标签", 3, 0, 5, (p, c) -> { })
				.register());
	}

	@Test
	void nonOverlappingLayoutBuilds() {
		ChasmGui gui = Chasm.gui("chasmtest", "ok").size(9, 3)
			.button("传送", 2, 0, (p, c) -> { })
			.button("治疗", 5, 0, (p, c) -> { })
			.register();
		assertEquals(2, gui.buttons().size());
		assertEquals(ChasmGuiText.cells("传送"), gui.buttons().get(0).widthCells(), "默认按文字长度取宽");
		assertTrue(gui.buttons().get(0).pixelWidth() >= ChasmGuiText.pixels("传送") + 4,
			"按钮实际像素宽必须容得下文字（这是不重叠的前提）");
	}

	@Test
	void slotFilterControlsWhatCanBeInserted() {
		ChasmStorageSlot filtered = new ChasmStorageSlot(0, 0, 0,
			stack -> stack.getMaxStackSize() == 1);
		assertTrue(filtered.mayPlace(new ItemStack(Items.DIAMOND_SWORD)), "单件装备可放入");
		assertFalse(filtered.mayPlace(new ItemStack(Items.DIRT, 64)), "64 个泥土应被拒绝");
		ChasmStorageSlot open = new ChasmStorageSlot(0, 0, 0);
		assertTrue(open.mayPlace(new ItemStack(Items.DIRT, 64)), "无过滤时不过滤（向后兼容）");
	}

	@Test
	void darkThemeAndContrastHelpersAreSane() {
		assertEquals(0xFFFFFFFF, ChasmGuiTheme.readableTextOn(0xFF101010), "深底给白字");
		assertEquals(0xFF1A1A1A, ChasmGuiTheme.readableTextOn(0xFFC6C6C6), "浅底给黑字（不会白底白字）");
		assertTrue(ChasmGuiTheme.DARK.autoContrastLabel(), "默认开启自动对比");
	}
}
