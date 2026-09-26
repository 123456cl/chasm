package api.chasm.random;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加权抽样池（Vose alias 法，O(1) 单次抽样）+ 预算 / 互斥 / 派生种子。
 *
 * <p><b>为什么需要它</b>：词缀池、稀有度掉落、宝石袋、刷怪表都需要"带权重抽样"，而朴素做法
 * （每次线性累加）在条目多、调用频繁时是 O(n)；alias 表建表 O(n) 一次，之后每次抽样 O(1)。</p>
 *
 * <p><b>三个高级语义</b>：</p>
 * <ul>
 *   <li><b>派生种子</b>：{@link Builder#seed(long)} 设基线种子；{@code sampleDerived(salt)}
 *       用同一基线 + 不同 salt 生成独立且可复现的随机流——同一世界的词缀/宝石/掉落互不串扰，
 *       又能精确复现（存档/回放/联机校验友好）。</li>
 *   <li><b>互斥分组</b>：{@link Builder#group(Object)} 之后的条目归入同一互斥组；
 *       {@link #sampleDistinct(int)} / {@link #sampleUntilBudget(double)} 保证同组最多出一件
 *       （如"同一词缀的不同等级只能出一个""三个前缀槽位不与同族冲突"）。</li>
 *   <li><b>预算</b>：每条目可带 {@code cost}（{@link Builder#add(Object, double, double)}），
 *       {@link #sampleUntilBudget(double)} 累计消耗直到预算耗尽即停，且**不超预算**、不重复抽取
 *       （稀有度点数、附魔等级上限、镶嵌槽价值等）。</li>
 * </ul>
 *
 * <p>线程友好：池体在 {@link #build()} 后不可变（除内部默认随机流），抽样所需状态全在方法栈上；
 * 需要跨线程/存档复现时用 {@code sample*(..., RandomSource)} 重载自行传入随机源。</p>
 *
 * @param <T> 条目类型
 */
public final class WeightedPool<T> {

	/** 空池单例（抽样返回 null / 空列表）。 */
	private static final WeightedPool<?> EMPTY = builder().build();

	private final List<T> values;
	private final double[] weights;
	private final double[] costs;
	private final int[] groupOf;
	private final int groupCount;
	private final double[] prob;
	private final int[] alias;
	private final long baseSeed;
	private RandomSource rng;

	private WeightedPool(List<T> values, double[] weights, double[] costs, int[] groupOf, int groupCount, long baseSeed) {
		this.values = List.copyOf(values);
		this.weights = weights;
		this.costs = costs;
		this.groupOf = groupOf;
		this.groupCount = groupCount;
		this.baseSeed = baseSeed;
		this.rng = RandomSource.create(baseSeed);
		this.prob = new double[weights.length];
		this.alias = new int[weights.length];
		buildAliasTable();
	}

	/** 空池（抽样返回 null / 空列表）。 */
	@SuppressWarnings("unchecked")
	public static <T> WeightedPool<T> empty() {
		return (WeightedPool<T>) EMPTY;
	}

	public static <T> Builder<T> builder() {
		return new Builder<>();
	}

	// ------------------------------------------------------------------ 构建

	/** 流式构建器（链式）。 */
	public static final class Builder<T> {
		private final List<T> values = new ArrayList<>();
		private final List<Double> weights = new ArrayList<>();
		private final List<Double> costs = new ArrayList<>();
		private final Map<Object, Integer> groups = new LinkedHashMap<>();
		private final List<Integer> groupOf = new ArrayList<>();
		private int currentGroup = -1;
		private long seed = 0L;

		/** 追加一条目（权重须 &gt; 0；cost 默认 1.0）。 */
		public Builder<T> add(T value, double weight) {
			return add(value, weight, 1.0);
		}

		/** 追加一条目并指定预算消耗 cost。 */
		public Builder<T> add(T value, double weight, double cost) {
			if (value == null) {
				throw new IllegalArgumentException("WeightedPool 条目不可为 null");
			}
			if (!(weight > 0.0) || Double.isNaN(weight) || Double.isInfinite(weight)) {
				throw new IllegalArgumentException("权重必须是有限正数，收到: " + weight);
			}
			if (Double.isNaN(cost) || cost < 0.0) {
				throw new IllegalArgumentException("cost 必须是非负有限数，收到: " + cost);
			}
			values.add(value);
			weights.add(weight);
			costs.add(cost);
			groupOf.add(currentGroup);
			return this;
		}

		/** 后续 {@code add} 的条目归入同一互斥组（同组最多抽中一件）。 */
		public Builder<T> group(Object groupKey) {
			if (groupKey == null) {
				currentGroup = -1;
			} else {
				currentGroup = groups.computeIfAbsent(groupKey, k -> groups.size());
			}
			return this;
		}

		/** 基线种子（配合 {@code sampleDerived(salt)} 派生独立可复现流）。 */
		public Builder<T> seed(long seed) {
			this.seed = seed;
			return this;
		}

		/** 构建不可变池（空池合法，抽样返回 null / 空列表）。 */
		public WeightedPool<T> build() {
			int n = values.size();
			double[] w = new double[n];
			double[] c = new double[n];
			int[] g = new int[n];
			for (int i = 0; i < n; i++) {
				w[i] = weights.get(i);
				c[i] = costs.get(i);
				g[i] = groupOf.get(i);
			}
			return new WeightedPool<>(values, w, c, g, groups.size(), seed);
		}
	}

	/** Vose alias 建表：O(n) 一次，之后抽样 O(1)。 */
	private void buildAliasTable() {
		int n = weights.length;
		if (n == 0) {
			return;
		}
		double total = 0.0;
		for (double w : weights) {
			total += w;
		}
		double[] scaled = new double[n];
		int[] small = new int[n];
		int[] large = new int[n];
		int smallCount = 0;
		int largeCount = 0;
		for (int i = 0; i < n; i++) {
			scaled[i] = weights[i] * n / total;
			if (scaled[i] < 1.0) {
				small[smallCount++] = i;
			} else {
				large[largeCount++] = i;
			}
		}
		while (smallCount > 0 && largeCount > 0) {
			int s = small[--smallCount];
			int l = large[--largeCount];
			prob[s] = scaled[s];
			alias[s] = l;
			scaled[l] = scaled[l] + scaled[s] - 1.0;
			if (scaled[l] < 1.0) {
				small[smallCount++] = l;
			} else {
				large[largeCount++] = l;
			}
		}
		while (largeCount > 0) {
			prob[large[--largeCount]] = 1.0;
		}
		while (smallCount > 0) {
			prob[small[--smallCount]] = 1.0;
		}
	}

	// ------------------------------------------------------------------ 查询

	public boolean isEmpty() {
		return values.isEmpty();
	}

	public int size() {
		return values.size();
	}

	/** 入选条目（只读，顺序 = 添加顺序）。 */
	public List<T> entries() {
		return values;
	}

	public double totalWeight() {
		double total = 0.0;
		for (double w : weights) {
			total += w;
		}
		return total;
	}

	public long baseSeed() {
		return baseSeed;
	}

	/** 重设内部随机流（复现/调试用）。 */
	public WeightedPool<T> reseed(long seed) {
		this.rng = RandomSource.create(seed);
		return this;
	}

	/** 某条目的权重（不存在返回 0）。 */
	public double weightOf(T value) {
		int i = values.indexOf(value);
		return i < 0 ? 0.0 : weights[i];
	}

	// ------------------------------------------------------------------ 抽样

	/** 单次抽样（内部随机流，baseSeed 起）。 */
	public T sample() {
		int idx = draw(rng, null);
		return idx < 0 ? null : values.get(idx);
	}

	/** 单次抽样（外部随机源，适合世界随机）。 */
	public T sample(RandomSource random) {
		int idx = draw(random, null);
		return idx < 0 ? null : values.get(idx);
	}

	/** 单次抽样（派生种子：同一 salt 结果可复现，不同 salt 独立流）。 */
	public T sampleDerived(long salt) {
		int idx = draw(RandomSource.create(deriveSeed(baseSeed, salt)), null);
		return idx < 0 ? null : values.get(idx);
	}

	/** 取出 count 个**互不重复**结果（同互斥组最多一件）。 */
	public List<T> sampleDistinct(int count) {
		return sampleDistinct(count, rng);
	}

	public List<T> sampleDistinct(int count, RandomSource random) {
		return drawMany(count, Double.POSITIVE_INFINITY, random);
	}

	public List<T> sampleDistinct(int count, long salt) {
		return drawMany(count, Double.POSITIVE_INFINITY, RandomSource.create(deriveSeed(baseSeed, salt)));
	}

	/** 抽样直到**预算耗尽**：累计 cost 不超过 budget，结果互不重复。 */
	public List<T> sampleUntilBudget(double budget) {
		return sampleUntilBudget(budget, rng);
	}

	public List<T> sampleUntilBudget(double budget, RandomSource random) {
		return drawMany(Integer.MAX_VALUE, budget, random);
	}

	public List<T> sampleUntilBudget(double budget, long salt) {
		return drawMany(Integer.MAX_VALUE, budget, RandomSource.create(deriveSeed(baseSeed, salt)));
	}

	// ------------------------------------------------------------------ 内部

	private List<T> drawMany(int count, double budget, RandomSource random) {
		List<T> out = new ArrayList<>();
		if (values.isEmpty() || count <= 0 || budget < 0.0) {
			return out;
		}
		int n = values.size();
		boolean[] takenItem = new boolean[n];
		boolean[] takenGroup = new boolean[groupCount];
		double remaining = budget;
		while (out.size() < count) {
			int idx = draw(random, excluded(takenItem, takenGroup));
			if (idx < 0) {
				break;
			}
			double cost = costs[idx];
			if (cost > remaining) {
				// 预算不足：严格不超支，停止（若首件就超额则返回空列表）
				break;
			}
			takenItem[idx] = true;
			if (groupOf[idx] >= 0) {
				takenGroup[groupOf[idx]] = true;
			}
			remaining -= cost;
			out.add(values.get(idx));
		}
		return out;
	}

	private boolean[] excluded(boolean[] takenItem, boolean[] takenGroup) {
		boolean[] blocked = new boolean[values.size()];
		for (int i = 0; i < blocked.length; i++) {
			blocked[i] = takenItem[i] || (groupOf[i] >= 0 && takenGroup[groupOf[i]]);
		}
		return blocked;
	}

	/**
	 * O(1) alias 抽样 + 屏蔽；屏蔽命中时最多重试 {@code MAX_RETRY} 次，
	 * 仍失败则退化为一次线性加权扫描（保证正确性，仅在大量条目被屏蔽时发生）。
	 */
	private int draw(RandomSource random, boolean[] blocked) {
		int n = weights.length;
		if (n == 0) {
			return -1;
		}
		for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
			int i = random.nextInt(n);
			int pick = random.nextDouble() < prob[i] ? i : alias[i];
			if (blocked == null || !blocked[pick]) {
				return pick;
			}
		}
		return drawLinear(random, blocked);
	}

	private int drawLinear(RandomSource random, boolean[] blocked) {
		double total = 0.0;
		for (int i = 0; i < weights.length; i++) {
			if (blocked == null || !blocked[i]) {
				total += weights[i];
			}
		}
		if (total <= 0.0) {
			return -1;
		}
		double r = random.nextDouble() * total;
		double acc = 0.0;
		int last = -1;
		for (int i = 0; i < weights.length; i++) {
			if (blocked != null && blocked[i]) {
				continue;
			}
			acc += weights[i];
			last = i;
			if (r < acc) {
				return i;
			}
		}
		return last;
	}

	/** SplitMix64 派生：同一 (baseSeed, salt) 恒定，不同 salt 独立。 */
	public static long deriveSeed(long baseSeed, long salt) {
		long z = baseSeed + salt * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** 屏蔽重试上限（超过则线性兜底）。 */
	private static final int MAX_RETRY = 8;

	@Override
	public String toString() {
		return "WeightedPool[size=" + values.size() + ", totalWeight=" + totalWeight()
			+ ", groups=" + groupCount + ", baseSeed=" + baseSeed + "]";
	}
}
