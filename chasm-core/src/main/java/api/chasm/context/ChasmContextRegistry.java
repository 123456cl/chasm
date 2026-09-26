package api.chasm.context;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModContext 注册表：以 modId 为键，维护每个模组的独立上下文（隔离边界）。
 *
 * <p><b>个人数据隔离</b>：每个使用 Chasm 的模组拥有独立的 {@link ModContext}——
 * 注册空间、物品控制台、数据空间、行为扩展全部按 modId 隔离，互不干扰。
 * 需要全局聚合时使用 {@link #all()}。</p>
 *
 * <p><b>SPI 暴露</b>：模组加载时，其声明（物品/行为/数据）会暴露到自己的 ModContext，
 * 任何模组均可通过 {@link ChasmContextRegistry#get(String)} 获取并做底层修改追加——
 * 即使目标模组闭源，只要它使用 Chasm 声明，就能被扩展。</p>
 */
public final class ChasmContextRegistry {

	private static final Map<String, ModContext> contexts = new ConcurrentHashMap<>();

	private ChasmContextRegistry() {
	}

	/**
	 * 获取（不存在则创建）指定模组的上下文。
	 *
	 * @param modId 模组命名空间
	 * @return 该模组的独立上下文
	 */
	public static ModContext get(String modId) {
		return contexts.computeIfAbsent(modId, ModContext::new);
	}

	/**
	 * 获取所有模组上下文（全局聚合视图）。
	 *
	 * @return 不可变集合，按需遍历做全局操作
	 */
	public static Collection<ModContext> all() {
		return contexts.values();
	}

	// —— 当前模组（扫描期间设置，供配方等声明归属） ——

	private static volatile String currentModId;

	/** 设置当前正在初始化的模组（由扫描器在 scan 开头调用）。 */
	public static void setCurrent(String modId) {
		currentModId = modId;
	}

	/**
	 * 当前正在初始化的模组 id；不在初始化上下文（尚未 {@code scan}）时返回 {@code null}。
	 *
	 * <p>与 {@link #current()} 的区别：不抛异常，供 DataGen 这类"尽力而为"的场景读取。</p>
	 */
	public static String currentIdOrNull() {
		return currentModId;
	}

	/**
	 * 获取当前正在初始化的模组上下文。
	 *
	 * @throws IllegalStateException 若不在模组初始化上下文（scan 之后）中调用
	 */
	public static ModContext current() {
		if (currentModId == null) {
			throw new IllegalStateException(
				"当前没有进行中的模组初始化（请先调用 ChasmRegistrar.scan，或在 onInitialize 中声明）");
		}
		return get(currentModId);
	}
}
