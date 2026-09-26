package api.chasm.data;

import api.chasm.log.ChasmLogger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * **声明式内容加载**：模组说"我的 JSON 长这样、放在哪、什么时候加载、加载完注册到哪"，
 * 框架负责扫描、解析、隔离错误、重载与注销。
 *
 * <p>为什么要有它：模块化模组（Tetra / 神化那类）都有几百个 JSON。此前每个模组都得自己写一遍
 * {@code SimpleSynchronousResourceReloadListener}、自己列表、自己 try/catch、自己想"文件删了怎么注销"
 * —— 全是重复劳动，而且**很容易漏掉"删除后不清理"**（旧内容留在注册表里）。</p>
 *
 * <pre>{@code
 * Chasm.content("mymod", "blueprints")
 *     .parse(MyBlueprint::fromJson)      // 你的格式，你说了算
 *     .target(Blueprints::register)      // 注册到哪都行（含贡献管线）
 *     .onRemove(Blueprints::unregister)  // 文件删掉时怎么注销（可选）
 *     .register();
 * }</pre>
 *
 * <h2>多消费者挂载（一份内容，多个落点）</h2>
 * <p>同一批 JSON 常常有**多个互不相干的消费者**：例如 Tetra 的 {@code data/tetra/modules/**}
 * 既要有"模块/槽位/属性"的加载器，又要有"外观分层"的加载器；{@code materials/**} 同理。
 * 以前这些消费者只能各自挂一个重载监听器 —— 而 Fabric 对**同一个监听器 id 只保留第一次**注册的
 * （第二次打一句 "Tried to register resource reload listener … twice!" 然后<b>丢弃</b>），
 * 于是后来的那个必须换一个 id 自己重扫一遍同一批文件（重复 IO、重复解析、两份 loaded 列表，
 * 而且两边对"文件被删掉"的处理还要各写一遍）。</p>
 *
 * <p>本框架的解法：**一个目录一个内容源，源上挂 N 个消费者**。扫描与 JSON 解析只做一次，
 * 每个消费者用自己的解析器与落点：</p>
 * <pre>{@code
 * // 拥有者（第一个注册的）照旧
 * ChasmContent.<Module>of("tetra", "modules").parse(Modules::parse).target(Modules::accept).register();
 *
 * // 第二个消费者：挂到同一份内容上（不新建监听器、不重扫文件）
 * ChasmDataRegistry.<Appearance>create("tetra/layers")
 *     .mount("tetra", "modules", Appearance::parse);
 * }</pre>
 * <p>挂载**与注册顺序无关**：先挂后注册会把消费者挂起，等拥有者注册时自动接上（见
 * {@link #mountExisting}）。</p>
 *
 * <p>隔离性：单个文件解析失败只跳过该文件并记日志，不影响其它文件与其它模组；
 * 每个消费者有自己的成功/失败计数与"上一轮加载的 id 列表"（删文件时逐个消费者精确注销）。</p>
 */
public final class ChasmContent<T> {

	private static final Gson GSON = new Gson();

	/** 触发方式：数据包重载 / 资源包重载 / 两者。 */
	public enum ReloadPolicy {
		/** 服务端数据包（{@code data/<ns>/<dir>/**}）—— 大多数玩法数据用这个。 */
		SERVER_DATA,
		/** 客户端资源包（{@code assets/<ns>/<dir>/**}）。 */
		CLIENT_DATA,
		/** 两边都监听。 */
		BOTH
	}

	/**
	 * 一个消费者的**解析口**（擦除后的形态）。
	 *
	 * <p>为什么不直接用 {@code BiFunction<ResourceLocation, JsonObject, Optional<U>>}：
	 * 泛型实参**不变**（{@code BiFunction<...,Optional<U>>} 不是
	 * {@code BiFunction<...,Optional<?>>} 的子类型），跨消费者的异构存放会立刻编译不过。
	 * 用这个接口把"擦除"收在一个地方，消费者各自的类型在 {@link #parseWith} 里转回来。</p>
	 */
	@FunctionalInterface
	interface DocParser {
		Optional<?> parse(ResourceLocation id, JsonObject json);
	}

	/** 一个消费者的**落点**（擦除后的形态）：值在这里转回该消费者自己的类型。 */
	@FunctionalInterface
	interface DocTarget {
		void accept(ResourceLocation id, Object value);
	}

	/**
	 * **一个消费者**：自己的解析器 + 自己的落点 + 自己的注销回调 + 自己的"上一轮加载的 id"。
	 *
	 * <p>用命名类而不是 record：内部的 {@code loaded} 列表是可变状态，record 会误导读者以为它不可变。</p>
	 */
	static final class Sink {

		/** 消费者身份（原始 parser + 原始 target）：适配器每次都会新建 lambda，只能按**原始对象**去重。 */
		record Origin(Object parser, Object target) {
		}

		private final DocParser parser;
		private final DocTarget target;
		private final Consumer<ResourceLocation> onRemove;
		private final String label;
		private final Origin origin;
		private final List<ResourceLocation> loaded = new ArrayList<>();

		private int lastOk;

		Sink(DocParser parser, DocTarget target, Consumer<ResourceLocation> onRemove, String label, Origin origin) {
			this.parser = parser;
			this.target = target;
			this.onRemove = onRemove;
			this.label = label == null ? "consumer" : label;
			this.origin = origin;
		}

		private Optional<?> parse(ResourceLocation id, JsonObject json) {
			return parser.parse(id, json);
		}
	}

	/** 把类型化解析器收成 {@link DocParser}（{@code Optional<U>} → {@code Optional<?>} 在 lambda 体内是合法赋值）。 */
	static <U> DocParser parseWith(BiFunction<ResourceLocation, JsonObject, Optional<U>> parser) {
		return parser::apply;
	}

	/** 把类型化落点收成 {@link DocTarget}（值在这里还原成 {@code U}）。 */
	@SuppressWarnings("unchecked")
	static <U> DocTarget targetWith(BiConsumer<ResourceLocation, U> target) {
		return (id, value) -> target.accept(id, (U) value);
	}

	/** 把"只吃对象、不要 id"的落点收成 {@link DocTarget}。 */
	@SuppressWarnings("unchecked")
	static <U> DocTarget consumerWith(Consumer<U> target) {
		return (id, value) -> target.accept((U) value);
	}

	private final String modId;
	private final String directory;
	private final ReloadPolicy policy;

	/** 全部消费者（第 0 个是"拥有者"，其余是挂载上来的）。 */
	private final List<Sink> sinks = new ArrayList<>();

	/** 第一消费者最近一次重载的条目数（{@link #loadedCount()} 的口径）。 */
	private volatile int lastCount;

	private ChasmContent(Builder<T> builder) {
		this.modId = builder.modId;
		this.directory = builder.directory;
		this.policy = builder.policy;
		for (Sink sink : builder.sinks) {
			sinks.add(sink);
		}
	}

	public static <T> Builder<T> of(String modId, String directory) {
		return new Builder<>(modId, directory);
	}

	/** 已加载条目数（第一消费者最近一次重载的结果，可给命令/调试界面用）。 */
	public int loadedCount() {
		return lastCount;
	}

	/** 全部消费者的总条目数（多消费者挂载后用来对账）。 */
	public int totalLoadedCount() {
		int total = 0;
		synchronized (sinks) {
			for (Sink sink : sinks) {
				total += sink.loaded.size();
			}
		}
		return total;
	}

	/** 已挂载的消费者数量。 */
	public int consumerCount() {
		synchronized (sinks) {
			return sinks.size();
		}
	}

	/** 本内容源已加载的 id（第一消费者的只读快照）。 */
	public List<ResourceLocation> loadedIds() {
		synchronized (sinks) {
			return sinks.isEmpty() ? List.of() : List.copyOf(sinks.get(0).loaded);
		}
	}

	public String modId() {
		return modId;
	}

	public String directory() {
		return directory;
	}

	/**
	 * **挂载一个新消费者**（可在 {@link #register()} 之前或之后调用；线程安全）。
	 *
	 * @param parser   该消费者自己的解析器（返回 empty = 这条跳过）
	 * @param target   该消费者的落点（带 id）
	 * @param onRemove 文件被删掉时如何注销（可为 null）
	 * @return 是否真的挂上了（重复挂载同一个 parser+target 会返回 false，不会重复投递）
	 */
	public <U> boolean mount(BiFunction<ResourceLocation, JsonObject, Optional<U>> parser,
							 BiConsumer<ResourceLocation, U> target, Consumer<ResourceLocation> onRemove) {
		return mountRaw(parseWith(parser), targetWith(target), onRemove, new Sink.Origin(parser, target));
	}

	/**
	 * 挂载的**原始形态**（泛型已在 {@link DocParser}/{@link DocTarget} 里擦除）。
	 *
	 * <p>存在的理由：{@code ChasmContent<?>}（已经注册好的源）只能接受擦除后的消费者；
	 * 而对外 API 必须带类型（否则每个调用点都要手写强转）。</p>
	 */
	boolean mountRaw(DocParser parser, DocTarget target, Consumer<ResourceLocation> onRemove, Sink.Origin origin) {
		if (parser == null || target == null) {
			return false;
		}
		Sink sink = new Sink(parser, target, onRemove,
			modId + "/" + directory + "#" + (consumerCount() + 1), origin);
		synchronized (sinks) {
			for (Sink existing : sinks) {
				if (origin != null && origin.equals(existing.origin)) {
					return false;   // 同一个消费者挂两次 = 重复投递，直接挡掉
				}
			}
			sinks.add(sink);
		}
		ChasmLogger.info(modId, "内容源 {}/{} 挂载了第 {} 个消费者", modId, directory, consumerCount());
		return true;
	}

	/** 挂载一个"只吃对象、不要 id"的消费者。 */
	public <U> boolean mountConsumer(BiFunction<ResourceLocation, JsonObject, Optional<U>> parser,
									 Consumer<U> target, Consumer<ResourceLocation> onRemove) {
		return mountRaw(parseWith(parser), consumerWith(target), onRemove, new Sink.Origin(parser, target));
	}

	/** 把"通用注册表"挂成一个消费者（accept + remove 就是它的读写口）。 */
	public <U> boolean mount(ChasmDataRegistry<U> registry,
							 BiFunction<ResourceLocation, JsonObject, Optional<U>> parser) {
		if (registry == null) {
			return false;
		}
		return mountRaw(parseWith(parser), targetWith(registry::accept), registry::remove,
			new Sink.Origin(parser, registry));
	}

	private void reload(ResourceManager manager) {
		// 1) 扫描一次，JSON 只解析一次（多消费者的收益就在这里：IO 与 Gson 解析不重复）
		Map<ResourceLocation, JsonObject> documents = new LinkedHashMap<>();
		Map<ResourceLocation, Resource> found = manager.listResources(directory,
			path -> path.getPath().endsWith(".json"));
		for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
			ResourceLocation file = entry.getKey();
			String path = file.getPath().substring(directory.length() + 1, file.getPath().length() - 5);
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath(file.getNamespace(), path);
			try (BufferedReader reader = entry.getValue().openAsReader()) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				if (json == null) {
					ChasmLogger.warn(modId, "内容 {} 不是 JSON 对象（已跳过）", file);
					continue;
				}
				documents.put(id, json);
			} catch (Exception e) {
				ChasmLogger.warn(modId, "内容 {} 读取失败（已跳过）: {}", file, e.toString());
			}
		}
		deliver(documents);
	}

	/**
	 * **把一批已解析的文档投递给全部消费者**（与数据包重载走同一条投递路径）。
	 *
	 * <p>单独抽出来是为了可测：单测可以直接喂一份 {@code id → JsonObject}，
	 * 断言"多消费者都收到了、删掉的 id 各自注销了"，不需要造一个假的 {@code ResourceManager}。</p>
	 */
	void deliver(Map<ResourceLocation, JsonObject> documents) {
		List<Sink> snapshot;
		synchronized (sinks) {
			snapshot = List.copyOf(sinks);
		}
		// 1) 逐个消费者注销上一轮内容：文件被删掉时它必须消失，否则注册表里会留幽灵
		for (Sink sink : snapshot) {
			if (sink.onRemove == null) {
				sink.loaded.clear();
				continue;
			}
			for (ResourceLocation id : List.copyOf(sink.loaded)) {
				try {
					sink.onRemove.accept(id);
				} catch (RuntimeException e) {
					ChasmLogger.warn(modId, "内容 {} 的消费者 {} 注销失败: {}", id, sink.label, e.toString());
				}
			}
			sink.loaded.clear();
		}

		// 2) 逐个消费者投递（单消费者失败不影响其它消费者）
		int files = 0;
		if (documents != null) {
			for (Map.Entry<ResourceLocation, JsonObject> entry : documents.entrySet()) {
				ResourceLocation id = entry.getKey();
				JsonObject json = entry.getValue();
				if (id == null || json == null) {
					continue;
				}
				files++;
				for (Sink sink : snapshot) {
					try {
						Optional<?> parsed = sink.parse(id, json);
						if (parsed == null || parsed.isEmpty()) {
							continue;
						}
						sink.target.accept(id, parsed.get());
						sink.loaded.add(id);
						sink.lastOk++;
					} catch (Exception e) {
						ChasmLogger.warn(modId, "内容 {} 的消费者 {} 解析失败（已跳过）: {}",
							id, sink.label, e.toString());
					}
				}
			}
		}
		if (!snapshot.isEmpty()) {
			lastCount = snapshot.get(0).loaded.size();
		}
		StringBuilder summary = new StringBuilder();
		for (Sink sink : snapshot) {
			summary.append(summary.length() == 0 ? "" : "，").append(sink.lastOk).append('条');
		}
		ChasmLogger.info(modId, "内容源 {}/{} 重载完成：{} 个文件 / {} 个消费者 → {}",
			modId, directory, files, snapshot.size(), summary);
	}

	private void registerListeners() {
		SourceListener listener = new SourceListener(this);
		if (policy == ReloadPolicy.SERVER_DATA || policy == ReloadPolicy.BOTH) {
			ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(listener);
		}
		if (policy == ReloadPolicy.CLIENT_DATA || policy == ReloadPolicy.BOTH) {
			ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(listener);
		}
	}

	/**
	 * 监听器实现（用**命名类**而不是匿名类：Fabric 的重载监听器接口对匿名实现有泛型/桥接问题，
	 * 命名类是本项目已验证可编译的写法，见 apoth-port 的 GemReloadListener）。
	 */
	static final class SourceListener implements SimpleSynchronousResourceReloadListener {

		private final ChasmContent<?> source;
		private final ResourceLocation id;

		SourceListener(ChasmContent<?> source) {
			this.source = source;
			this.id = ResourceLocation.fromNamespaceAndPath(source.modId, source.directory);
		}

		@Override
		public ResourceLocation getFabricId() {
			return id;
		}

		@Override
		public void onResourceManagerReload(ResourceManager manager) {
			source.reload(manager);
		}
	}

	// ==================================================================================
	// 全局登记表：一个（modId, 目录）一个内容源，消费者挂上去
	// ==================================================================================

	/** 已注册的内容源（键 = modId + 目录）。 */
	private static final Map<String, ChasmContent<?>> REGISTERED = new java.util.concurrent.ConcurrentHashMap<>();

	/** 先挂载、后注册时暂存消费者（键 = modId + 目录）。 */
	private static final Map<String, List<PendingSink>> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

	/** 一次"抢跑"的挂载请求。 */
	private record PendingSink(DocParser parser, DocTarget target, Consumer<ResourceLocation> onRemove,
							   Sink.Origin origin) {
	}

	/**
	 * **把消费者挂到（可能还不存在的）内容源上**（与注册顺序无关）。
	 *
	 * <p>场景：外观管线在模组入口里排在模块加载器**之前**初始化。此时 {@code tetra/modules}
	 * 还没有拥有者，直接找内容源会落空 —— 本方法把它记成"待挂载"，等拥有者 {@code register()} 时接上。</p>
	 *
	 * @return true = 当场挂上了；false = 已排入待挂载队列（拥有者注册时自动接上）
	 */
	public static <U> boolean mountExisting(String modId, String directory,
											BiFunction<ResourceLocation, JsonObject, Optional<U>> parser,
											BiConsumer<ResourceLocation, U> target,
											Consumer<ResourceLocation> onRemove) {
		if (modId == null || directory == null || parser == null || target == null) {
			return false;
		}
		String key = modId + "/" + directory;
		ChasmContent<?> existing = REGISTERED.get(key);
		DocParser rawParser = parseWith(parser);
		DocTarget rawTarget = targetWith(target);
		Sink.Origin origin = new Sink.Origin(parser, target);
		if (existing != null) {
			return existing.mountRaw(rawParser, rawTarget, onRemove, origin);
		}
		PENDING.computeIfAbsent(key, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>())
			.add(new PendingSink(rawParser, rawTarget, onRemove, origin));
		return false;
	}

	/** 查一个内容源（不存在 → null）。 */
	public static ChasmContent<?> find(String modId, String directory) {
		return modId == null || directory == null ? null : REGISTERED.get(modId + "/" + directory);
	}

	/** 已注册的内容源数量（调试/测试）。 */
	public static int sourceCount() {
		return REGISTERED.size();
	}

	/** 待挂载的消费者数量（调试/测试）。 */
	public static int pendingCount() {
		int total = 0;
		for (List<PendingSink> list : PENDING.values()) {
			total += list.size();
		}
		return total;
	}

	/** 清空登记表（单测用；运行时不调用）。 */
	static void clearRegistryForTesting() {
		REGISTERED.clear();
		PENDING.clear();
	}

	/** 构建器。 */
	public static final class Builder<T> {
		private final String modId;
		private final String directory;
		private final List<Sink> sinks = new ArrayList<>();
		private BiFunction<ResourceLocation, JsonObject, Optional<T>> parser;
		private Consumer<T> target;
		/** 带 id 的注册回调（通用注册表要按 id 维护反查与重载清理，见 {@link ChasmDataRegistry}）。 */
		private java.util.function.BiConsumer<ResourceLocation, T> targetWithId;
		private Consumer<ResourceLocation> onRemove;
		private ReloadPolicy policy = ReloadPolicy.SERVER_DATA;

		private Builder(String modId, String directory) {
			if (modId == null || modId.isBlank() || directory == null || directory.isBlank()) {
				throw new IllegalArgumentException("内容源需要非空的 modId 与目录名");
			}
			this.modId = modId;
			this.directory = directory;
		}

		/** 你的格式，你说了算：返回 empty 表示"这条跳过"。 */
		public Builder<T> parse(BiFunction<ResourceLocation, JsonObject, Optional<T>> parser) {
			this.parser = parser;
			return this;
		}

		/** 解析成功后注册到哪（注册表、贡献管线、界面数据……都行）。 */
		public Builder<T> target(Consumer<T> target) {
			this.target = target;
			return this;
		}

		/**
		 * 解析成功后注册到哪（**带 id 版本**）。
		 *
		 * <p>需要 id 的场景：按 id 维护"反查表 + 重载时精确清理"（例如通用注册表
		 * {@link ChasmDataRegistry}）。只要 id 没有意义就用 {@link #target(Consumer)}。</p>
		 */
		public Builder<T> target(java.util.function.BiConsumer<ResourceLocation, T> target) {
			this.targetWithId = target;
			return this;
		}

		/**
		 * **追加一个消费者**（多消费者挂载）：本内容源扫描到的每个 JSON 都会再喂给它一份。
		 *
		 * <p>与 {@link #parse}/{@link #target} 的区别只是"类型不同"——框架内部一视同仁。
		 * 这样一份内容可以有任意多个落点，而**只挂一个重载监听器、只解析一次 JSON**。</p>
		 */
		public <U> Builder<T> mount(BiFunction<ResourceLocation, JsonObject, Optional<U>> parser,
									BiConsumer<ResourceLocation, U> target, Consumer<ResourceLocation> onRemove) {
			if (parser != null && target != null) {
				sinks.add(new Sink(parseWith(parser), targetWith(target), onRemove,
					modId + "/" + directory + "#" + (sinks.size() + 1), new Sink.Origin(parser, target)));
			}
			return this;
		}

		/** 追加一个"只吃对象、不要 id"的消费者。 */
		public <U> Builder<T> mountConsumer(BiFunction<ResourceLocation, JsonObject, Optional<U>> parser,
											Consumer<U> target, Consumer<ResourceLocation> onRemove) {
			if (parser != null && target != null) {
				sinks.add(new Sink(parseWith(parser), consumerWith(target), onRemove,
					modId + "/" + directory + "#" + (sinks.size() + 1), new Sink.Origin(parser, target)));
			}
			return this;
		}

		/** 重载前如何注销旧内容（可选；不传则只做"重新注册"）。 */
		public Builder<T> onRemove(Consumer<ResourceLocation> onRemove) {
			this.onRemove = onRemove;
			return this;
		}

		/** 触发方式。 */
		public Builder<T> reloadPolicy(ReloadPolicy policy) {
			this.policy = policy;
			return this;
		}

		/**
		 * **测试/离线构建**：建出内容源但**不挂监听器、不进全局登记表**。
		 *
		 * <p>给单测用（{@code ChasmContentMultiConsumerTest}）：可以直接
		 * {@link ChasmContent#deliver} 喂文档，断言多消费者的投递与注销，
		 * 不需要 Fabric 的资源管理器。</p>
		 */
		ChasmContent<T> buildForTesting() {
			Sink owner = ownerSink();
			ChasmContent<T> content = new ChasmContent<>(this);
			content.sinks.clear();
			content.sinks.add(owner);
			content.sinks.addAll(sinks);
			return content;
		}

		/** 拥有者消费者（第一个；类型化的那个）。 */
		private Sink ownerSink() {
			if (parser == null || (target == null && targetWithId == null)) {
				throw new IllegalStateException("内容源 " + modId + "/" + directory + " 缺少 parse 或 target");
			}
			DocParser docParser = parseWith(parser);
			DocTarget docTarget = targetWithId != null ? targetWith(targetWithId) : consumerWith(target);
			Object originTarget = targetWithId != null ? targetWithId : target;
			return new Sink(docParser, docTarget, onRemove, modId + "/" + directory,
				new Sink.Origin(parser, originTarget));
		}

		/** 注册（模组初始化期调用一次）。 */
		public ChasmContent<T> register() {
			String key = modId + "/" + directory;
			synchronized (REGISTERED) {
				ChasmContent<?> existing = REGISTERED.get(key);
				if (existing != null) {
					// **同一个（modId, 目录）重复注册不再报错丢弃**：第一次的源就是这一份内容的拥有者，
					// 后来的注册请求按"再挂一个消费者"处理（以前在这里直接 return，导致第二个消费者
					// 完全拿不到数据 —— 实机症状是"78 条成功"与"0 个模块"同时出现，见 CodeWiki §49 补）。
					ChasmLogger.warn(modId, "内容源 {} 已有拥有者，本次注册按【挂载消费者】处理", key);
					Sink owner = ownerSink();
					if (!existing.mountRaw(owner.parser, owner.target, onRemove, owner.origin)) {
						ChasmLogger.warn(modId, "内容源 {} 的重复消费者未挂载（同一个 parser+target 已在表里）", key);
					}
					return (ChasmContent<T>) existing;
				}
				ChasmContent<T> content = new ChasmContent<>(this);
				// 拥有者在前，Builder 上挂的消费者在后（顺序 = 投递顺序，稳定可预期）
				content.sinks.clear();
				content.sinks.add(ownerSink());
				content.sinks.addAll(sinks);
				// 抢跑的消费者（先 mount、后 register）在这里接上
				List<PendingSink> pending = PENDING.remove(key);
				if (pending != null) {
					for (PendingSink p : pending) {
						content.mountRaw(p.parser(), p.target(), p.onRemove(), p.origin());
					}
				}
				REGISTERED.put(key, content);
				content.registerListeners();
				ChasmLogger.info(modId, "内容源已注册：{}/{}（{}，{} 个消费者）",
					modId, directory, policy, content.consumerCount());
				return content;
			}
		}
	}
}
