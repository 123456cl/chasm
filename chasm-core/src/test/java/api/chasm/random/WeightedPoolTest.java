package api.chasm.random;

import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WeightedPool：alias 分布正确性 / 互斥分组 / 预算 / 派生种子可复现。 */
class WeightedPoolTest {

	@Test
	void singleEntryAlwaysDrawn() {
		WeightedPool<String> pool = WeightedPool.<String>builder().add("only", 1.0).build();
		for (int i = 0; i < 50; i++) {
			assertEquals("only", pool.sample());
		}
	}

	@Test
	void emptyPoolReturnsNullAndEmpty() {
		WeightedPool<String> pool = WeightedPool.empty();
		assertTrue(pool.isEmpty());
		assertNull(pool.sample());
		assertTrue(pool.sampleDistinct(3).isEmpty());
		assertTrue(pool.sampleUntilBudget(10.0).isEmpty());
	}

	@Test
	void aliasDistributionMatchesWeights() {
		WeightedPool<String> pool = WeightedPool.<String>builder()
			.add("common", 7.0)
			.add("rare", 3.0)
			.build();
		RandomSource random = RandomSource.create(12345L);
		int rare = 0;
		int rounds = 200_000;
		for (int i = 0; i < rounds; i++) {
			if ("rare".equals(pool.sample(random))) {
				rare++;
			}
		}
		double ratio = rare / (double) rounds;
		assertTrue(Math.abs(ratio - 0.3) < 0.01, "稀有度占比应≈0.30，实测 " + ratio);
	}

	@Test
	void sampleDistinctRespectsExclusiveGroups() {
		WeightedPool<String> pool = WeightedPool.<String>builder()
			.group("prefix")
			.add("sharpness", 5.0)
			.add("smite", 5.0)
			.group("suffix")
			.add("swift", 5.0)
			.add("heavy", 5.0)
			.build();
		// 两个互斥组 → 一次抽样最多每组各出一件（共 2 件），即便请求 4 件也不会同组重复
		List<String> drawn = pool.sampleDistinct(4, 999L);
		assertEquals(2, drawn.size(), "两组互斥条目最多出 2 件");
		long prefixCount = drawn.stream().filter(s -> s.equals("sharpness") || s.equals("smite")).count();
		long suffixCount = drawn.stream().filter(s -> s.equals("swift") || s.equals("heavy")).count();
		assertEquals(1, prefixCount, "同互斥组最多一件");
		assertEquals(1, suffixCount, "同互斥组最多一件");

		// 无分组时应当取满请求数量
		WeightedPool<String> flat = WeightedPool.<String>builder()
			.add("a", 1.0).add("b", 1.0).add("c", 1.0).build();
		assertEquals(3, flat.sampleDistinct(3, 5L).size());
		assertEquals(3, flat.sampleDistinct(10, 5L).size(), "超出条目数只返回全部条目");
	}

	@Test
	void sampleUntilBudgetNeverOverspends() {
		WeightedPool<String> pool = WeightedPool.<String>builder()
			.add("cheap", 1.0, 2.0)
			.add("mid", 1.0, 3.0)
			.add("pricey", 1.0, 4.0)
			.build();
		for (long salt = 0; salt < 200; salt++) {
			List<String> drawn = pool.sampleUntilBudget(5.0, salt);
			double spent = drawn.stream().mapToDouble(s -> switch (s) {
				case "cheap" -> 2.0;
				case "mid" -> 3.0;
				default -> 4.0;
			}).sum();
			assertTrue(spent <= 5.0, "预算 5 不可超支，实测 " + spent + " -> " + drawn);
			assertTrue(drawn.size() >= 1, "预算足够一件时应至少出一件");
		}
	}

	@Test
	void derivedSeedIsReproducibleAndSaltIndependent() {
		WeightedPool<String> pool = WeightedPool.<String>builder()
			.seed(42L)
			.add("a", 1.0)
			.add("b", 1.0)
			.add("c", 1.0)
			.build();
		StringBuilder first = new StringBuilder();
		StringBuilder second = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			first.append(pool.sampleDerived(7L));
			second.append(pool.sampleDerived(7L));
		}
		assertEquals(first.toString(), second.toString(), "同一 salt 必须可复现");

		StringBuilder otherSalt = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			otherSalt.append(pool.sampleDerived(8L));
		}
		assertNotEquals(first.toString(), otherSalt.toString(), "不同 salt 应为独立流");
	}

	@Test
	void invalidInputsRejectedEarly() {
		WeightedPool.Builder<String> builder = WeightedPool.builder();
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> builder.add("bad", 0.0));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> builder.add("bad", -1.0));
		assertNotNull(WeightedPool.<String>builder().add("ok", 1.0).build().sample());
	}
}
