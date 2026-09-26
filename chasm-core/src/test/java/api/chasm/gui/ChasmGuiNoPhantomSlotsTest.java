package api.chasm.gui;

import api.chasm.Chasm;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **不许凭空造槽位**（真实事故的回归测试）。
 *
 * <h2>事故经过</h2>
 * <p>真实 Tetra 加工台移植后，界面左上角出现"一排像快捷栏的东西"：无框、但鼠标移上去会泛白高亮，
 * 而且把剑放进中间的菱形槽后它**同时出现在左上角**。根因是 {@link ChasmGuiBuilder#register()} 里一条
 * 遗留默认值："没声明任何网格单元 → 把整个 9×1 默认网格全铺成存储槽"。
 * 于是"自己声明了 4 个像素槽"的界面被**额外**补了 9 个真槽位，而且索引**从 0 重新计数**，
 * 与像素槽 0..3 撞号 → 同一个容器格被两个 Slot 引用（所以物品在两处同时显示）。</p>
 *
 * <p>这些槽不是"画出来的东西"，而是货真价实的原版 {@code Slot}：物品由原版 {@code renderSlot} 画、
 * 悬停泛白由原版 {@code renderSlotHighlight}(0x80FFFFFF) 画、还能被 Shift 快捷移动塞进物品。
 * 所以正确处理是**根本不创建它们**，而不是想办法把它们藏起来。</p>
 */
class ChasmGuiNoPhantomSlotsTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** 声明式界面 + 像素槽：槽位数量必须**正好等于声明数**，索引 0..n-1 不重复。 */
	@Test
	void declarativeGuiWithPixelSlotsGetsNoExtraGridSlots() {
		ChasmGui gui = Chasm.gui("chasmtest", "phantom_decl")
			.panelSize(320, 240)
			.slotPx(152, 58)
			.slotPx(194, 108)
			.slotPx(222, 108)
			.slotPx(250, 108)
			.source(state -> List.of())
			.register();

		assertEquals(4, gui.storageSlots().size(),
			"声明了 4 个像素槽就不能再补默认网格槽（补出来的就是左上角那排幽灵槽）");

		Set<Integer> indices = new HashSet<>();
		for (ChasmStorageSlot slot : gui.storageSlots()) {
			assertTrue(indices.add(slot.index()),
				"容器索引必须唯一：重复会让同一个容器格被两个 Slot 引用，物品在两处同时显示");
			assertTrue(slot.pixelX() >= 100,
				"槽位坐标必须来自声明（幽灵网格槽在 (8,18) 一排，x 会 < 100）");
		}
	}

	/** 声明式界面且一个槽位都没声明 → 就是 0 个，不许凭空补 9 个。 */
	@Test
	void declarativeGuiWithoutSlotsHasNone() {
		ChasmGui gui = Chasm.gui("chasmtest", "phantom_decl_empty")
			.panelSize(320, 240)
			.source(state -> List.of())
			.register();

		assertEquals(0, gui.storageSlots().size(), "声明式界面没声明槽位就不该有槽位");
	}

	/** 老的纯网格界面行为必须保留：没声明单元、没像素槽 = 整个网格都是存储槽。 */
	@Test
	void plainGridGuiStillFillsWholeGrid() {
		ChasmGui gui = Chasm.gui("chasmtest", "phantom_grid")
			.size(9, 1)
			.register();

		assertEquals(9, gui.storageSlots().size(), "纯网格界面的默认铺满行为不能被我改坏");
		for (int i = 0; i < 9; i++) {
			assertEquals(i, gui.storageSlots().get(i).index());
			assertEquals(8 + i * 18, gui.storageSlots().get(i).pixelX());
		}
	}
}
