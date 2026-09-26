package api.chasm.model;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * **按"栈的数据组件内容"记忆化图层求值**（框架内部件，包内可见）。
 *
 * <h2>为什么要有它</h2>
 * <p>{@link ItemLayerProvider} 是**按栈**求值的，而物品渲染每帧都会问一次外观
 * （1.21.1 的 {@code ItemRenderer.getModel} → {@code ItemOverrides.resolve}，
 * 见 {@code ItemRenderer#getModel} 反汇编第 531-538 行）。没有记忆化就会出现
 * "同一把剑每帧重算一遍图层"，真实 Tetra 也要靠缓存兜住
 * （{@code _ref/Tetra-1.20/.../client/model/ModularOverrideList.java:45-48} 的 Guava Cache：
 * 1000 条 / 5 分钟）。</p>
 *
 * <h2>键 = 数据组件内容 + 动画帧</h2>
 * <p>帧（{@link ItemLayerProvider#frameOf}）是**同一个栈在不同时刻的两种外观**（拉弓动画），
 * 所以它必须进键：每个数据组件内容下挂一个**少量帧条目的小列表**（帧集合有界 ≤ 5 种），
 * 命中时逐个比对帧名 —— 绝不把别的帧的图层表交出去，也不会因为查过另一个帧就把本条挤掉。</p>
 *
 * <h2>键 = 数据组件内容</h2>
 * <p>键取 {@code stack.getComponents().hashCode()}：
 * {@code ItemStack#getComponents()} 直接返回内部的 {@code PatchedDataComponentMap}（零分配，
 * 见 {@code ItemStack#getComponents} 反汇编第 0-17 行），而它的 {@code hashCode()}
 * 是**内容哈希**（{@code prototype.hashCode() + patch.hashCode() * 31}，
 * 见 {@code PatchedDataComponentMap#hashCode} 反汇编第 0-22 行），
 * 且 {@code ItemStack} 本身不覆写 equals/hashCode（身份语义）。</p>
 * <p>于是：查表 {@code O(1)}、键计算 {@code O(组件数)}（通常 0-6 个组件）、热路径零分配、
 * 组件一变（换模块、换材料、掉耐久）键就变 → 自动重算。**不扫全表**。</p>
 *
 * <h2>失效</h2>
 * <ul>
 *   <li>资源重载（F3+T / {@code /reload} / 换资源包）：模型会整批重烘焙，包装模型与它的缓存一起重建
 *       —— 见 {@code ModelLoadingPlugin} 的 javadoc「every time resource are (re)loaded」与
 *       {@code ModelBakery.bakeModels} 反汇编第 0-16 行（每个 top-level 模型都会重新走烘焙钩子）。</li>
 *   <li>只改数据、不重载模型：调 {@link ChasmItemLayerModels#invalidateCaches()}（内部 epoch +1，
 *       下一次查表整表清空）。</li>
 * </ul>
 *
 * <h2>绝不 NPE / 绝不崩渲染线程</h2>
 * <p>provider 返回 null、返回含 null 贴图的层、抛 {@code RuntimeException} 甚至 {@code Error}，
 * 这里都兜住并退化为"无图层"（= 回落到原来的单张贴图）。</p>
 */
final class ChasmItemLayerCache {

	/** 单表条目上限：超了整表清空。渲染线程上不做逐条淘汰（不引入额外复杂度与分配）。 */
	static final int MAX_ENTRIES = 512;

	/** 全局脏标记：{@link ChasmItemLayerModels#invalidateCaches()} 让它 +1。 */
	private static final AtomicLong EPOCH = new AtomicLong();

	/** 同一个栈最多缓存几个帧（真实帧集合：undrawn / draw_0..2 / loaded，个位数）。 */
	private static final int MAX_FRAMES_PER_STACK = 8;

	private final Item item;
	/**
	 * 数据组件内容 → 该栈的少量（帧, 图层表）条目。
	 *
	 * <p>为什么是**一个小列表**而不是单条：帧是同一个栈的不同外观（拉弓动画），一张弓在同一帧里会用到
	 * {@code undrawn} 与 {@code draw_1} 两种外观（手上是拉的、界面里是静止的），单条键会把另一个帧挤掉，
	 * 于是每次都在重算、也拿不到稳定实例。帧集合有界（≤ 5 种），所以桶内线性扫 + 超上限丢弃即可，
	 * 渲染线程上命中路径零分配（只做比较与一次 put）。</p>
	 */
	private final Int2ObjectOpenHashMap<List<Entry>> entries = new Int2ObjectOpenHashMap<>();
	/** 数据组件内容 → 最近一次**求值或命中**用的帧（染色路径用，见 frameFor）。 */
	private final Int2ObjectOpenHashMap<String> frames = new Int2ObjectOpenHashMap<>();
	private long seenEpoch = Long.MIN_VALUE;
	/** 总条目数（= 各桶大小之和，用于 MAX_ENTRIES 上限与 size() 调试）。 */
	private int size;

	/** 一条缓存：帧名 + 该帧下的图层表（不可变）。 */
	private record Entry(String frame, List<ItemLayer> layers) {
	}

	ChasmItemLayerCache(Item item) {
		this.item = item;
	}

	/** 让所有缓存下一次查表时清空（数据重载用）。 */
	static void invalidateAll() {
		EPOCH.incrementAndGet();
	}

	/** 当前脏标记（包装模型用它决定要不要丢掉"图层表实例 → 已建模型"的映射）。 */
	static long epoch() {
		return EPOCH.get();
	}

	/**
	 * 该栈的图层（按数据组件内容记忆化）。
	 *
	 * @param stack 物品栈；null / 空栈 → 空表
	 * @return **永不为 null** 的只读表（可能为空 = 没有图层，调用方回落到原模型）
	 */
	List<ItemLayer> layersOf(ItemStack stack) {
		return layersOf(stack, "");
	}

	/**
	 * 该栈在指定帧下的图层（按数据组件内容 + 帧名记忆化）。
	 *
	 * @param stack 物品栈；null / 空栈 → 空表
	 * @param frame 动画帧/状态名；null 按空串（= 无帧，与 {@link #layersOf(ItemStack)} 等价）
	 * @return **永不为 null** 的只读表（可能为空 = 没有图层，调用方回落到原模型）
	 */
	List<ItemLayer> layersOf(ItemStack stack, String frame) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}
		String wanted = frame == null ? "" : frame;
		long epoch = EPOCH.get();
		if (epoch != seenEpoch) {
			clearAll();
			seenEpoch = epoch;
		}
		int key = contentKey(stack);
		List<Entry> bucket = entries.get(key);
		if (bucket != null) {
			for (int i = 0; i < bucket.size(); i++) {
				Entry cached = bucket.get(i);
				if (cached.frame().equals(wanted)) {
					// 命中也要记帧：染色路径（没有实体入参）靠 frameFor 对齐刚刚用的那一帧
					frames.put(key, wanted);
					return cached.layers();
				}
			}
		}
		List<ItemLayer> layers = resolve(stack, wanted);
		if (size >= MAX_ENTRIES) {
			clearAll(); // 表满了整表清空：不做 LRU 链表，渲染线程上要的是可预测
			bucket = null;
		}
		if (bucket == null || bucket.size() >= MAX_FRAMES_PER_STACK) {
			if (bucket != null) {
				size -= bucket.size(); // 换掉这个栈的旧帧（帧集合有界，丢最旧的一批即可）
			}
			bucket = new ArrayList<>(4);
			entries.put(key, bucket);
		}
		bucket.add(new Entry(wanted, layers));
		size++;
		frames.put(key, wanted);
		return layers;
	}

	/** 整表清空（脏标记 / 超上限共用；条目数一起归零）。 */
	private void clearAll() {
		entries.clear();
		frames.clear();
		size = 0;
	}

	/** 当前条目数（= 各栈的（内容, 帧）条目总数；单测/调试用）。 */
	int size() {
		return size;
	}

	/** 数据组件内容 → int 键。见类注释的"键 = 数据组件内容"。 */
	private static int contentKey(ItemStack stack) {
		// getComponents() 零分配；PatchedDataComponentMap.hashCode() 是内容哈希、零分配
		return stack.getComponents().hashCode();
	}

	/**
	 * **该栈最近一次求值用的帧**（染色路径没有持有者，只能沿用它）。
	 *
	 * <p>为什么需要：图层染色走 {@code ItemColors.getColor(stack, tintIndex)} —— 那条路上**没有实体**
	 * （反汇编 {@code ItemRenderer#renderQuadList} 只传 stack 与 tintIndex），而帧要靠实体算
	 * （真实 {@code ModularBowItem.getProgress:408-414} 读 {@code getUseItemRemainingTicks}）。
	 * 于是"第 i 层是哪张图/什么颜色"必须与刚发生的 {@code resolve} 用同一个帧，否则拉弓动画帧的
	 * 颜色会张冠李戴。这里记住每个"数据组件内容"最近一次求值用的帧，查表 O(1)。</p>
	 */
	String frameFor(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}
		String frame = frames.get(contentKey(stack));
		return frame == null ? "" : frame;
	}

	/** 真正去问 provider，并清洗结果（丢 null 层 / null 贴图）。 */
	private List<ItemLayer> resolve(ItemStack stack, String frame) {
		List<ItemLayer> raw;
		try {
			raw = ChasmItemLayers.layersOf(stack, frame);
		} catch (Throwable t) {
			// layersOf 已经吞掉 RuntimeException；这里再兜 Error（贴图/模块数据坏掉时也可能抛）
			warnOnce(t);
			return List.of();
		}
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<ItemLayer> clean = null;
		for (int i = 0; i < raw.size(); i++) {
			ItemLayer layer = raw.get(i);
			if (layer == null || layer.texture() == null) {
				if (clean == null) {
					clean = new ArrayList<>(raw.size());
					clean.addAll(raw.subList(0, i)); // 惰性复制：只有真出现坏层才复制
				}
				continue;
			}
			if (clean != null) {
				clean.add(layer);
			}
		}
		if (clean == null) {
			// 防御性复制：provider 之后改自己的表也不影响缓存（List.copyOf 顺带拒绝 null 元素）
			return List.copyOf(raw);
		}
		return clean.isEmpty() ? List.of() : List.copyOf(clean);
	}

	private void warnOnce(Throwable t) {
		if (!warned) {
			warned = true;
			api.chasm.log.ChasmLogger.warn("chasm",
				"物品 {} 的外观图层缓存求值失败（按无图层处理，客户端不崩）: {}", item, t.toString());
		}
	}

	private boolean warned;
}
