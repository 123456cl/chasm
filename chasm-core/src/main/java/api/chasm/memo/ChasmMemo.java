package api.chasm.memo;

import api.chasm.log.ChasmLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * **通用记忆化表**（框架能力）—— "按 key 算一次、状态变了整表作废"。
 *
 * <h2>为什么要有它</h2>
 * <p>端口里已经有几处"自建缓存"：需求判定缓存（{@code TetraRequirementCache} 的两张
 * {@code LinkedHashMap} + LRU + 手工 {@code clear}）、外观层索引、按槽位折叠的属性表……
 * 每一处都得自己写一遍"容量上限 / 命中统计 / 失效 / 线程安全"，重复且容易漏（漏一次就是
 * "数据重载后还读到旧值"这类幽灵 bug）。本类把这四件事收成一份实现。</p>
 *
 * <h2>失效模型：版本号（不轮询、不挂监听器）</h2>
 * <pre>{@code
 * private static final ChasmMemo<Key, Value> CACHE =
 *         ChasmMemo.<Key, Value>create("tetra/requirements", TetraSchematics::revision);
 * }</pre>
 * <p>每次访问先读一次 {@link IntSupplier}（O(1)），与上次清空时的值不同就整表清空。
 * 版本号由**数据源**自己维护（数据重载 / 注册变更时自增），所以：</p>
 * <ul>
 *   <li>不需要挂监听器，也不会有"忘了清"的路径 —— 只要源会动，版本就会动；</li>
 *   <li>手工注册（非重载路径）后只要源自增版本，缓存自动跟上；</li>
 *   <li>没有时间戳、没有轮询、没有后台线程。</li>
 * </ul>
 *
 * <h2>复杂度与线程安全</h2>
 * <p>命中 {@code O(1)}（LRU 的 {@code LinkedHashMap(accessOrder=true)}），未命中
 * {@code O(1)} 插入 + 一次 {@code loader}；淘汰 {@code O(1)}。所有读写都在同一把锁里
 * （渲染线程与逻辑线程都会用，锁粒度是整表 —— 临界区里只有哈希查找与一次 put，没有回调）。</p>
 *
 * <h2>null 语义</h2>
 * <p>{@link #computeIfAbsent} 允许 {@code loader} 返回 {@code null}：**null 也会被缓存**
 * （内部用哨兵），这样"查不到"这种结果同样不会反复重算 —— 这正是反查类缓存最需要的
 * （{@code TetraRequirementCache.OUTCOME_CACHE} 就是靠它避免反复扫产出表）。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public final class ChasmMemo<K, V> {

	/** null 的哨兵（{@code LinkedHashMap} 允许 null 值，但显式哨兵让"没算过/算出来是 null"可区分）。 */
	private static final Object NULL = new Object();

	/** 默认容量上限。 */
	public static final int DEFAULT_MAX_ENTRIES = 512;

	private final String name;
	private final IntSupplier revision;
	private final Map<K, Object> entries;

	private int maxEntries = DEFAULT_MAX_ENTRIES;
	private volatile int cachedRevision = Integer.MIN_VALUE;

	private long hits;
	private long misses;

	private ChasmMemo(String name, IntSupplier revision) {
		this.name = name == null || name.isBlank() ? "unnamed" : name;
		// 版本号缺失 → 恒 0：此时表永不自动失效，只能靠 invalidateAll（与"根本没有版本概念"等价）
		this.revision = revision == null ? () -> 0 : revision;
		this.entries = new LinkedHashMap<>(64, 0.75F, true);
	}

	/** 建表：{@code name} 只用于日志/统计，{@code revision} 是**源数据的版本号**（可为 null）。 */
	public static <K, V> ChasmMemo<K, V> create(String name, IntSupplier revision) {
		return new ChasmMemo<>(name, revision);
	}

	/** 容量上限（超出按 LRU 淘汰最近最少使用的条目）。 */
	public ChasmMemo<K, V> maxEntries(int cap) {
		synchronized (entries) {
			this.maxEntries = Math.max(1, cap);
		}
		return this;
	}

	public String name() {
		return name;
	}

	/** 当前源版本（{@link IntSupplier} 的即时读取值）。 */
	public int revision() {
		return revision.getAsInt();
	}

	/**
	 * 取一条（不计算）。版本变了会先整表作废。
	 *
	 * @return 缓存的值的副本语义：命中原样返回；**没算过 → null**（注意 null 也可能是"算出来就是 null"，
	 *         要区分请用 {@link #contains(Object)}）
	 */
	public V get(K key) {
		if (key == null) {
			return null;
		}
		ensureFresh();
		synchronized (entries) {
			if (!entries.containsKey(key)) {
				misses++;
				return null;
			}
			hits++;
			return unwrap(entries.get(key));
		}
	}

	/** 该键是否已经有缓存条目（含"缓存了 null"）。 */
	public boolean contains(K key) {
		if (key == null) {
			return false;
		}
		ensureFresh();
		synchronized (entries) {
			return entries.containsKey(key);
		}
	}

	/**
	 * 记忆化取数：命中直接返回，未命中调用 {@code loader} 并把结果（**含 null**）存起来。
	 *
	 * <p>{@code key} 为 null 或 {@code loader} 为 null 时**不缓存**、直接返回 null ——
	 * 挡 null 是硬要求（渲染线程一次 NPE 就是崩客户端，见 CodeWiki §49 补三）。</p>
	 */
	public V computeIfAbsent(K key, Function<K, V> loader) {
		if (key == null || loader == null) {
			return null;
		}
		ensureFresh();
		synchronized (entries) {
			Object cached = entries.get(key);
			if (cached != null) {
				hits++;
				return unwrap(cached);
			}
			misses++;
			Object computed = loader.apply(key);
			entries.put(key, computed == null ? NULL : computed);
			return unwrap(entries.get(key));
		}
	}

	/**
	 * 与 {@link #computeIfAbsent} 相同，但 {@code loader} 抛异常时**不缓存**并把异常吞掉（返回 null）。
	 *
	 * <p>渲染线程路径专用：缓存层不应该成为新的崩溃点。</p>
	 */
	public V computeSafely(K key, Function<K, V> loader) {
		if (key == null || loader == null) {
			return null;
		}
		ensureFresh();
		synchronized (entries) {
			Object cached = entries.get(key);
			if (cached != null) {
				hits++;
				return unwrap(cached);
			}
			misses++;
			V computed;
			try {
				computed = loader.apply(key);
			} catch (RuntimeException | LinkageError e) {
				ChasmLogger.warn(name, "记忆化计算失败（未缓存）: {}", String.valueOf(e));
				return null;
			}
			entries.put(key, computed == null ? NULL : computed);
			return computed;
		}
	}

	/** 写入一条（覆盖）。 */
	public void put(K key, V value) {
		if (key == null) {
			return;
		}
		ensureFresh();
		synchronized (entries) {
			entries.put(key, value == null ? NULL : value);
			trim();
		}
	}

	/** 手工放一条 null（等价"算过，结果是 null"）。 */
	public void putNull(K key) {
		put(key, null);
	}

	/** 整表清空（版本号同步到当前值，避免下次访问再清一遍）。 */
	public void invalidateAll() {
		synchronized (entries) {
			entries.clear();
			cachedRevision = revision.getAsInt();
		}
	}

	/** 按键谓词精确失效（例如"某条原理图变了"）。O(条目数)。 */
	public void invalidateIf(Predicate<K> filter) {
		if (filter == null) {
			return;
		}
		synchronized (entries) {
			entries.keySet().removeIf(filter);
		}
	}

	/** 当前条目数。 */
	public int size() {
		synchronized (entries) {
			return entries.size();
		}
	}

	public long hits() {
		synchronized (entries) {
			return hits;
		}
	}

	public long misses() {
		synchronized (entries) {
			return misses;
		}
	}

	/** 清命中计数（统计窗口重置；不影响缓存内容）。 */
	public void resetStats() {
		synchronized (entries) {
			hits = 0;
			misses = 0;
		}
	}

	/** 统计文本（启动日志/调试界面用，证明缓存真的在起作用）。 */
	public String stats() {
		synchronized (entries) {
			return String.format("%s：命中 %d / 未命中 %d / 当前 %d 条（上限 %d，源版本 %d）",
				name, hits, misses, entries.size(), maxEntries, cachedRevision);
		}
	}

	/** 打一行统计日志。 */
	public void logStats() {
		ChasmLogger.info(name, stats());
	}

	// ------------------------------------------------------------------ 内部

	/** 版本变了 → 整表作废。O(1)。 */
	private void ensureFresh() {
		int current = revision.getAsInt();
		if (current == cachedRevision) {
			return;
		}
		synchronized (entries) {
			if (current != cachedRevision) {
				entries.clear();
				cachedRevision = current;
			}
		}
	}

	private void trim() {
		while (entries.size() > maxEntries) {
			java.util.Iterator<K> iterator = entries.keySet().iterator();
			if (!iterator.hasNext()) {
				return;
			}
			iterator.next();
			iterator.remove();
		}
	}

	@SuppressWarnings("unchecked")
	private V unwrap(Object raw) {
		return raw == NULL ? null : (V) raw;
	}

	/** 全部键的快照（调试/测试用，顺序 = LRU 顺序）。 */
	public List<K> keys() {
		synchronized (entries) {
			return List.copyOf(new ArrayList<>(entries.keySet()));
		}
	}

	@Override
	public String toString() {
		return "ChasmMemo[" + name + ", " + size() + "/" + maxEntries + "]";
	}
}
