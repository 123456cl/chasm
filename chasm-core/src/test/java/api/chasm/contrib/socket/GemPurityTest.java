package api.chasm.contrib.socket;

import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯度阶梯与分档数值（纯逻辑，不需要 Minecraft bootstrap）。 */
class GemPurityTest {

	private static final String NS = "test_purity_" + System.nanoTime();

	@Test
	void ladderOrderAndComparisons() {
		GemPurity a = GemPurity.register(NS + "_a", "甲", "%s", 0xFF111111, 1.0);
		GemPurity b = GemPurity.register(NS + "_b", "乙", "乙的%s", 0xFF222222, 1.0);
		GemPurity c = GemPurity.register(NS + "_c", "丙", "丙的%s", 0xFF333333, 1.0);

		assertTrue(b.atLeast(a));
		assertTrue(c.atLeast(a));
		assertFalse(a.atLeast(b));
		assertEquals(b, a.next());
		assertEquals(c, b.next());
		assertEquals(c, c.next(), "最高档的 next 是自身");
		assertEquals(c, GemPurity.max(a, c));
		// 静态版叫 startingAt（与实例版 atLeast 区分）：不低于 b 的档位 = b、c
		assertTrue(GemPurity.startingAt(b).contains(b));
		assertTrue(GemPurity.startingAt(b).contains(c));
		assertFalse(GemPurity.startingAt(c).contains(b));
		assertEquals("乙的甲", b.formatName("甲"), "名字模板要生效");
		assertEquals("甲", a.formatName("甲"), "%s 模板原样替换");
	}

	@Test
	void duplicateRegistrationRejected() {
		String path = NS + "_dup";
		GemPurity.register(path, "重复", "%s", 0xFF000000, 1.0);
		assertThrows(IllegalStateException.class,
			() -> GemPurity.register(path, "重复2", "%s", 0xFF000000, 1.0));
	}

	@Test
	void weightedRollStaysInPool() {
		GemPurity only = GemPurity.register(NS + "_solo", "独", "%s", 0xFF444444, 1.0);
		RandomSource rand = RandomSource.create(1234L);
		for (int i = 0; i < 50; i++) {
			assertEquals(only, GemPurity.roll(rand, java.util.List.of(only)));
		}
		assertEquals(only, GemPurity.roll(rand, java.util.List.of(only)));
	}

	@Test
	void tieredValuesResolvePerPurityAndRejectUnknown() {
		GemPurity lo = GemPurity.register(NS + "_lo", "低", "%s", 0xFF555555, 1.0);
		GemPurity mid = GemPurity.register(NS + "_mid", "中", "%s", 0xFF666666, 1.0);
		GemPurity hi = GemPurity.register(NS + "_hi", "高", "%s", 0xFF777777, 1.0);

		TieredValues values = TieredValues.builder()
			.put(lo.path(), 1.0F)
			.put(mid.path(), 2.5F)
			.build();

		assertEquals(1.0F, values.resolve(lo));
		assertEquals(2.5F, values.resolve(mid));
		assertEquals(0.0F, values.resolve(hi), "未列出的档位 = 不支持该加成");
		assertFalse(values.supports(hi));
		assertTrue(values.supports(mid));
		assertEquals(lo, values.minPurity(), "最低支持档位");
		assertThrows(IllegalStateException.class, () -> TieredValues.builder().build());
	}
}
