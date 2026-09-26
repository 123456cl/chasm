package api.chasm.item;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 物品注释：追加/幂等/精确移除/清空 + 渲染位置（名字下方）。 */
class ChasmItemNotesTest {

	private static final ResourceLocation RETIRED = ResourceLocation.fromNamespaceAndPath("test", "retired");
	private static final ResourceLocation BOSS = ResourceLocation.fromNamespaceAndPath("test", "boss_drop");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		ChasmItemNotesTooltip.init();
	}

	@Test
	void addAppendAndRemoveByIdArePrecise() {
		ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
		assertTrue(ChasmItemNotes.add(stack, ChasmItemNote.of(RETIRED, "已退役")));
		assertTrue(ChasmItemNotes.add(stack, ChasmItemNote.of(BOSS, "Boss 掉落", 0xFFFF7043, 1)));
		assertEquals(2, ChasmItemNotes.list(stack).size());
		assertTrue(ChasmItemNotes.has(stack, RETIRED));

		// 同 id 重复写入：内容相同则不动（避免无谓物品同步），内容不同则覆盖
		assertFalse(ChasmItemNotes.add(stack, ChasmItemNote.of(RETIRED, "已退役")), "完全相同的注释不应重写");
		assertTrue(ChasmItemNotes.add(stack, ChasmItemNote.of(RETIRED, "已退役（改）")), "同 id 不同内容应覆盖");
		assertEquals(2, ChasmItemNotes.list(stack).size(), "覆盖不应新增行");

		// 精确移除某一条
		assertTrue(ChasmItemNotes.remove(stack, RETIRED));
		assertFalse(ChasmItemNotes.has(stack, RETIRED));
		assertTrue(ChasmItemNotes.has(stack, BOSS), "移除一条不应影响其它注释");
		assertFalse(ChasmItemNotes.remove(stack, RETIRED), "重复移除返回 false");

		assertTrue(ChasmItemNotes.clear(stack));
		assertTrue(ChasmItemNotes.list(stack).isEmpty());
	}

	@Test
	void notesRenderUnderTheItemName() {
		ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
		ChasmItemNotes.add(stack, ChasmItemNote.of(RETIRED, "已退役：后续劫难不再生效", 0xFFFF7043, 0));
		List<Component> lines = new ArrayList<>();
		lines.add(stack.getHoverName());        // 模拟原版先放入名字
		lines.add(Component.literal("属性行"));   // 再放入属性行
		ChasmItemTooltips.appendAll(stack, TooltipFlag.Default.NORMAL, lines);

		assertEquals(3, lines.size());
		assertEquals("已退役：后续劫难不再生效", lines.get(1).getString(), "注释必须插在物品名下方");
		assertEquals("属性行", lines.get(2).getString(), "原有内容顺序不变");
	}

	@Test
	void notesSortByOrderThenId() {
		ItemStack stack = new ItemStack(Items.STICK);
		ChasmItemNotes.add(stack, ChasmItemNote.of(BOSS, "B", 0, 5));
		ChasmItemNotes.add(stack, ChasmItemNote.of(RETIRED, "A", 0, 1));
		List<ChasmItemNote> notes = ChasmItemNotes.list(stack);
		assertEquals("A", notes.get(0).textKey());
		assertEquals("B", notes.get(1).textKey());
	}
}
