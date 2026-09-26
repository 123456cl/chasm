package api.chasm.data;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * **数据注册表**（框架能力）—— 一份"id → 数据对象"的可重载集合。
 *
 * <h2>解决什么问题</h2>
 * <p>数据包加载时逐文件给回 {@code (id, 对象)}。没有它，端口只能把对象塞进
 * 手写的 hash 表、再用"前缀 / 名字里的下划线"去猜关联（脆、且一改数据就崩）。
 * 有了它，一切关联都走 id 查找，并能在数据包重载时<b>自增失效</b>。</p>
 *
 * <h2>热重载语义</h2>
 * <p>{@link #load} 会把解析结果喂进本表，并注册移除回调：重载时被删掉的 id 会自动从表中消失。
 * 每次变更都会让 {@link #all()} 的快照失效并触发监听器，所以上层缓存
 * （UI 列表、合成预览、属性折叠结果）只要挂一个监听器即可，不必自己判断重载时机。</p>
 *
 * @param <T> 数据对象类型
 */
public final class ChasmDataRegistry<T> {

	private final String name;
	private final Map<ResourceLocation, T> byId = new LinkedHashMap<>();
	private final List<Runnable> listeners = new ArrayList<>();
	private volatile List<T> snapshot = List.of();

	private ChasmDataRegistry(String name) {
		this.name = name;
	}

	public static <T> ChasmDataRegistry<T> create(String name) {
		return new ChasmDataRegistry<>(name);
	}

	/**
	 * 用本表接管一个数据目录：解析 → 存入本表（带 id）。
	 *
	 * <pre>{@code
	 * private static final ChasmDataRegistry<TetraMaterial> MATERIALS =
	 *         ChasmDataRegistry.<TetraMaterial>create("tetra/materials")
	 *                 .load("tetra", "materials", TetraMaterials::parse);
	 * }</pre>
	 */
	public ChasmContent<T> load(String modId, String directory,
	                            BiFunction<ResourceLocation, JsonObject, Optional<T>> parser) {
		return ChasmContent.<T>of(modId, directory)
				.parse(parser)
				.target(this::accept)
				.onRemove(this::remove)
				.register();
	}

	/**
	 * **把本表挂成某个内容源的额外消费者**（多消费者挂载；本表不自建重载监听器）。
	 *
	 * <p>用途：同一个数据目录已经有拥有者（例如 {@code tetra/modules} 归模块加载器），
	 * 而本表只想再消费一遍同一批 JSON（例如外观分层）。与 {@link #load} 的区别：</p>
	 * <ul>
	 *   <li>{@code load} = "这个目录归我，我建监听器"；</li>
	 *   <li>{@code mount} = "我挂到那个目录的内容源上，复用它的扫描与 JSON 解析"。</li>
	 * </ul>
	 *
	 * <p><b>与注册顺序无关</b>：拥有者还没注册时会先挂起，等它 {@code register()} 时自动接上
	 * （见 {@link ChasmContent#mountExisting}）。反复挂载同一个 parser 只会生效一次。</p>
	 *
	 * @param modId      内容源的模组 id（如 {@code tetra}）
	 * @param directory  内容源目录（如 {@code modules}）
	 * @param parser     本表自己的解析器
	 * @return 本表（便于链式书写）
	 */
	public ChasmDataRegistry<T> mount(String modId, String directory,
									  BiFunction<ResourceLocation, JsonObject, Optional<T>> parser) {
		ChasmContent.mountExisting(modId, directory, parser, this::accept, this::remove);
		return this;
	}

	/** 数据加载回调：写入一条（重载时覆盖旧值）。 */
	public void accept(ResourceLocation id, T value) {
		synchronized (byId) {
			byId.put(id, value);
			snapshot = null;
		}
		notifyListeners();
	}

	/** 数据加载回调：移除一条（重载时该文件被删掉）。 */
	public void remove(ResourceLocation id) {
		synchronized (byId) {
			if (byId.remove(id) == null) {
				return;
			}
			snapshot = null;
		}
		notifyListeners();
	}

	/** 校验用：一条都没加载出来通常意味着目录名写错或数据没进 jar。 */
	public int size() {
		synchronized (byId) {
			return byId.size();
		}
	}

	public boolean isEmpty() {
		return size() == 0;
	}

	public String name() {
		return name;
	}

	/** 按 id 查找（id 为 null → 空结果；{@code ConcurrentHashMap.get(null)} 会抛 NPE，所以必须挡住）。 */
	public Optional<T> find(ResourceLocation id) {
		if (id == null) {
			return Optional.empty();
		}
		synchronized (byId) {
			return Optional.ofNullable(byId.get(id));
		}
	}

	public T get(ResourceLocation id) {
		return find(id).orElse(null);
	}

	/**
	 * 按 path 查找（忽略命名空间）。数据里同一个 path 一般只属于一个命名空间，
	 * 但**不假设**这点：多个匹配时按插入顺序取第一个，并可用 {@link #findAllByPath} 全取。
	 */
	public Optional<T> findByPath(String path) {
		if (path == null) {
			return Optional.empty();
		}
		String bare = path.indexOf(':') >= 0 ? path.substring(path.indexOf(':') + 1) : path;
		synchronized (byId) {
			for (Map.Entry<ResourceLocation, T> e : byId.entrySet()) {
				if (e.getKey().getPath().equals(bare)) {
					return Optional.of(e.getValue());
				}
			}
		}
		return Optional.empty();
	}

	public T getByPath(String path) {
		return findByPath(path).orElse(null);
	}

	public List<T> findAllByPath(String path) {
		List<T> out = new ArrayList<>();
		if (path == null) {
			return out;
		}
		String bare = path.indexOf(':') >= 0 ? path.substring(path.indexOf(':') + 1) : path;
		synchronized (byId) {
			for (Map.Entry<ResourceLocation, T> e : byId.entrySet()) {
				if (e.getKey().getPath().equals(bare)) {
					out.add(e.getValue());
				}
			}
		}
		return out;
	}

	/** 挂 id 的写法（{@code "tetra:iron"} 或 {@code "iron"} 都能命中）。 */
	public Optional<T> find(String idOrPath) {
		if (idOrPath == null) {
			return Optional.empty();
		}
		return idOrPath.indexOf(':') >= 0
				? find(ResourceLocation.tryParse(idOrPath))
				: findByPath(idOrPath);
	}

	public T getByIdOrPath(String idOrPath) {
		return find(idOrPath).orElse(null);
	}

	/** 只读快照（缓存友好：不复制、重载后自动换新对象）。 */
	public List<T> all() {
		List<T> local = snapshot;
		if (local == null) {
			synchronized (byId) {
				local = snapshot;
				if (local == null) {
					local = List.copyOf(byId.values());
					snapshot = local;
				}
			}
		}
		return local;
	}

	public Set<ResourceLocation> ids() {
		synchronized (byId) {
			return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(byId.keySet()));
		}
	}

	public Optional<T> firstMatch(Predicate<T> filter) {
		for (T value : all()) {
			if (filter.test(value)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}

	public List<T> matching(Predicate<T> filter) {
		List<T> out = new ArrayList<>();
		for (T value : all()) {
			if (filter.test(value)) {
				out.add(value);
			}
		}
		return out;
	}

	/**
	 * 注册"重载后我这份缓存作废"的回调。
	 * <p>注意：数据加载期间的每一次写入都会触发（重载是成百上千次写入），
	 * 所以监听器里只应做"标脏"，不要做重活。</p>
	 */
	public void addListener(Runnable listener) {
		synchronized (listeners) {
			listeners.add(listener);
		}
	}

	private void notifyListeners() {
		synchronized (listeners) {
			for (Runnable r : listeners) {
				r.run();
			}
		}
	}

	@Override
	public String toString() {
		return "ChasmDataRegistry[" + name + ", " + size() + "]";
	}
}
