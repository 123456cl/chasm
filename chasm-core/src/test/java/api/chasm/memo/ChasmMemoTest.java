package api.chasm.memo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChasmMemo} 的行为断言：命中/未命中、**按版本号失效**、null 也缓存、LRU 上限、null 键安全。
 *
 * <p>这些断言直接对应端口 {@code TetraRequirementCache} 依赖的四条语义（见该类 javadoc）：
 * 键里不含玩家 → 命中 O(1)；数据重载 → 整表失效；"查不到"也要缓存；渲染线程调用绝不 NPE。</p>
 */
class ChasmMemoTest {

	@Test
	void hitsCacheUntilRevisionChanges() {
		AtomicInteger revision = new AtomicInteger();
		AtomicInteger computed = new AtomicInteger();
		ChasmMemo<String, Integer> memo = ChasmMemo.<String, Integer>create("t", revision::get);

		assertEquals(7, memo.computeIfAbsent("a", key -> {
			computed.incrementAndGet();
			return 7;
		}));
		assertEquals(7, memo.computeIfAbsent("a", key -> {
			computed.incrementAndGet();
			return 99;
		}));
		assertEquals(1, computed.get(), "同一版本内只算一次");
		assertEquals(1, memo.hits());
		assertEquals(1, memo.misses());

		revision.incrementAndGet();
		assertNull(memo.get("a"), "版本变了以后旧值必须不可见（数据重载后不能读到旧值）");
		assertEquals(0, memo.size(), "整表已按新版本作废");
		assertEquals(99, memo.computeIfAbsent("a", key -> {
			computed.incrementAndGet();
			return 99;
		}));
		assertEquals(2, computed.get(), "版本变了必须重算");
	}

	@Test
	void cachesNullResults() {
		AtomicInteger computed = new AtomicInteger();
		ChasmMemo<String, String> memo = ChasmMemo.create("t", null);
		for (int i = 0; i < 3; i++) {
			assertNull(memo.computeIfAbsent("missing", key -> {
				computed.incrementAndGet();
				return null;
			}));
		}
		assertEquals(1, computed.get(), "算出来是 null 也要缓存（反查类缓存靠它避免反复扫表）");
		assertTrue(memo.contains("missing"));
		assertNull(memo.get("missing"));
	}

	@Test
	void nullKeyAndNullLoaderAreSafe() {
		ChasmMemo<String, String> memo = ChasmMemo.create("t", null);
		assertNull(memo.computeIfAbsent(null, key -> "x"));
		assertNull(memo.computeIfAbsent("a", null));
		assertNull(memo.get(null));
		assertFalse(memo.contains(null));
		memo.put(null, "ignored");
		assertEquals(0, memo.size());
	}

	@Test
	void loaderFailureIsSwallowedAndNotCached() {
		AtomicInteger attempts = new AtomicInteger();
		ChasmMemo<String, String> memo = ChasmMemo.create("t", null);
		for (int i = 0; i < 2; i++) {
			assertNull(memo.computeSafely("boom", key -> {
				attempts.incrementAndGet();
				throw new IllegalStateException("loader 故意失败");
			}));
		}
		assertEquals(2, attempts.get(), "失败不缓存（下次仍会重试）");
		assertFalse(memo.contains("boom"));
	}

	@Test
	void lruEvictionKeepsBoundedSize() {
		ChasmMemo<Integer, Integer> memo = ChasmMemo.<Integer, Integer>create("t", null).maxEntries(2);
		memo.put(1, 1);
		memo.put(2, 2);
		memo.put(3, 3);   // 淘汰最久未用的 1
		assertEquals(2, memo.size());
		assertFalse(memo.contains(1));
		assertTrue(memo.contains(3));
	}

	@Test
	void invalidateIfDropsSelectedKeysOnly() {
		ChasmMemo<String, String> memo = ChasmMemo.create("t", null);
		memo.put("tetra:sword/howling", "a");
		memo.put("tetra:single/trident", "b");
		memo.invalidateIf(key -> key.startsWith("tetra:sword/"));
		assertEquals(1, memo.size());
		assertTrue(memo.contains("tetra:single/trident"));
	}

	@Test
	void sameInstanceReturnedWithoutCopy() {
		ChasmMemo<String, Object> memo = ChasmMemo.create("t", null);
		Object value = new Object();
		assertSame(value, memo.computeIfAbsent("k", key -> value));
		assertSame(value, memo.get("k"));
	}
}
