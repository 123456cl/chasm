package api.chasm.gui;

import com.mojang.serialization.Codec;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 界面数据状态：整数通道夹取/范围校验、大值脏合并与 codec 往返、客户端平滑。 */
class ChasmGuiStateTest {

	private static ChasmGuiState state() {
		return new ChasmGuiState(
			List.of(new ChasmGuiState.IntSlot("mana", 10, 0, 100),
				new ChasmGuiState.IntSlot("temp", 0, -20, 20)),
			List.of(new ChasmGuiState.ValueKey<>("status", Codec.STRING, "空闲"),
				new ChasmGuiState.ValueKey<>("count", Codec.INT, 0)));
	}

	@Test
	void intValuesClampToDeclaredRange() {
		ChasmGuiState s = state();
		assertEquals(10, s.intValue("mana"));
		assertEquals(100, s.setInt("mana", 999), "超上限应夹取");
		assertEquals(100, s.intValue("mana"));
		assertEquals(0, s.setInt("mana", -5), "超下限应夹取");
		assertEquals(-20, s.setInt("temp", -999));
	}

	@Test
	void containerDataViewIsBackedByTheSameArray() {
		ChasmGuiState s = state();
		var data = s.containerData();
		assertEquals(2, data.getCount());
		assertEquals(10, data.get(0));
		s.setInt("mana", 42);
		assertEquals(42, data.get(0), "整数槽视图必须与状态同源（客户端由原版包写入同一数组）");
		// 模拟客户端收到原版数据包：原版调用 set
		data.set(0, 7);
		assertEquals(7, s.intValue("mana"));
	}

	@Test
	void wireRangeIsEnforcedAtDeclaration() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> new ChasmGuiState.IntSlot("energy", 0, 0, 1_000_000));
		assertTrue(e.getMessage().contains("16 位"), "错误信息应说明 16 位限制");
		assertTrue(e.getMessage().contains("state("), "错误信息应引导改用大值通道");
		// 边界内可用
		new ChasmGuiState.IntSlot("ok", 0, ChasmGuiState.WIRE_MIN, ChasmGuiState.WIRE_MAX);
	}

	@Test
	void invalidDeclarationsAreRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> new ChasmGuiState.IntSlot("x", 5, 10, 20), "默认值越界应报错");
		assertThrows(IllegalArgumentException.class,
			() -> new ChasmGuiState.IntSlot("x", 0, 20, 10), "min>max 应报错");
		assertThrows(IllegalArgumentException.class,
			() -> new ChasmGuiState(List.of(
				new ChasmGuiState.IntSlot("dup", 0, 0, 10),
				new ChasmGuiState.IntSlot("dup", 0, 0, 10)), List.of()), "重复整数键应报错");
	}

	@Test
	void unknownKeyFailsFastWithHint() {
		ChasmGuiState s = state();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.intValue("nope"));
		assertTrue(e.getMessage().contains("mana"), "错误信息应列出已声明的键");
		assertThrows(IllegalArgumentException.class, () -> s.value("nope"));
	}

	@Test
	void largeValueOnlySyncsWhenChanged() {
		ChasmGuiState s = state();
		// 打开时全量推一次
		assertTrue(s.hasDirty());
		Map<String, Tag> first = s.encodeDirty();
		assertEquals(2, first.size(), "首次应全量（status + count）");
		assertTrue(first.containsKey("status"));
		assertFalse(s.hasDirty());
		assertTrue(s.encodeDirty().isEmpty(), "无变化不应再发包");

		// 值未变 → 不置脏
		s.setValue("status", "空闲");
		assertFalse(s.hasDirty(), "写入相同值不应触发同步");

		// 多次修改合并为一次
		s.setValue("status", "工作");
		s.setValue("count", 5);
		s.setValue("status", "过热");
		Map<String, Tag> second = s.encodeDirty();
		assertEquals(2, second.size(), "同 tick 多次修改应合并成一次发送，且只含变化键");
		assertFalse(second.containsKey("mana"));
		assertFalse(s.hasDirty());
	}

	@Test
	void largeValueRoundTripsThroughNbt() {
		ChasmGuiState server = state();
		server.setValue("status", "运转中");
		server.setValue("count", 1234);
		CompoundTag packet = new CompoundTag();
		server.encodeDirty().forEach(packet::put);

		ChasmGuiState client = state();
		assertEquals("空闲", client.value("status"));
		client.receiveAll(packet);
		assertEquals("运转中", client.value("status"));
		assertEquals(1234, (int) client.value("count"));
	}

	@Test
	void clientIgnoresUnknownKeysAndBadPayloads() {
		ChasmGuiState client = state();
		CompoundTag tag = new CompoundTag();
		tag.put("ghost", StringTag.valueOf("x"));
		tag.put("count", StringTag.valueOf("不是整数"));
		client.receiveAll(tag);
		assertEquals(0, (int) client.value("count"), "解码失败应保留旧值而不是崩溃");
		assertEquals("空闲", client.value("status"));
	}

	@Test
	void smoothingConvergesToTarget() {
		ChasmGuiState s = state();
		s.setInt("mana", 100);
		s.resetSmooth();
		assertEquals(100.0, s.smooth("mana"), 0.001, "resetSmooth 后应对齐当前值");
		s.setInt("mana", 0);              // 目标变为 0，平滑值仍停在 100
		assertEquals(100.0, s.smooth("mana"), 0.001);
		s.tickSmooth(0.5);                // 100 → 50
		assertEquals(50.0, s.smooth("mana"), 0.001);
		s.tickSmooth(0.5);                // 50 → 25
		assertEquals(25.0, s.smooth("mana"), 0.001);
		for (int i = 0; i < 40; i++) {
			s.tickSmooth(0.5);
		}
		assertEquals(0.0, s.smooth("mana"), 0.01, "最终应收敛到目标值");
	}

	@Test
	void snapshotAndDescribeAreObservable() {
		ChasmGuiState s = state();
		s.setValue("count", 9);
		CompoundTag snap = s.snapshot();
		assertEquals(10, snap.getInt("mana"));
		assertEquals(9, snap.getInt("count"));
		assertTrue(s.describe().contains("mana=10"));
		assertEquals(4, s.keys().size());
	}
}
