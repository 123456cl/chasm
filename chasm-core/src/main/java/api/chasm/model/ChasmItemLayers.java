package api.chasm.model;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **物品外观分层注册表**（上层 API —— 给"由数据决定外观"的物品用）。
 *
 * <h2>解决什么问题</h2>
 * <p>框架原先只会给物品画一张平面贴图（DataGen 的 {@code generateFlatItem}）。真实模组的模块化物品
 * （例如 Tetra 的剑：剑刃 + 剑柄 + 护手 + 剑尾，各自还有材料变体）外观是**多层贴图叠出来的**，
 * 层由物品的数据组件决定，不是一张静态贴图能表达的。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * ChasmItemLayers.register(MODULAR_SWORD, stack -> List.of(
 *     new ItemLayer(bladeTexture, bladeTint),
 *     new ItemLayer(hiltTexture, hiltTint)));
 * }</pre>
 *
 * <h2>兼容底线</h2>
 * <p><b>没有注册的物品行为完全不变</b>：{@link #providerOf} 返回 {@code null}、{@link #layersOf} 返回空表，
 * 模型子系统据此回落到原来的单张贴图。</p>
 *
 * <h2>缓存</h2>
 * <p>注册表本身是 O(1) 查表；**按栈求值的结果由模型子系统按"栈的数据组件内容"做记忆化**，
 * 实现方不必自己缓存（但实现方自己加缓存也不冲突）。</p>
 */
public final class ChasmItemLayers {

	private static final Map<Item, ItemLayerProvider> PROVIDERS = new ConcurrentHashMap<>();

	private ChasmItemLayers() {
	}

	/** 注册某物品的外观图层来源（重复注册以最后一次为准）。 */
	public static void register(Item item, ItemLayerProvider provider) {
		if (item == null || provider == null) {
			return;
		}
		PROVIDERS.put(item, provider);
		ensureClientHook();
	}

	/**
	 * **客户端接线（懒注册）**：第一次注册外观来源时把
	 * {@link ChasmItemLayerModels}（模型加载插件）挂上 Fabric 的模型加载 API。
	 *
	 * <p>为什么懒注册而不是写进 {@code fabric.mod.json} 的客户端入口点：
	 * {@code ModelLoadingPlugin.register} 只是往静态表追加（{@code ModelLoadingPluginManager
	 * .registerPlugin} 源码第 35-39 行），不需要"客户端初始化期"，而懒注册让专用服务器
	 * 连客户端类都不会加载（{@code EnvType} 判断在方法第一条语句，JVM 到那时才解析后面的类引用）。</p>
	 *
	 * <p><b>时机要求</b>：provider 要在**首次资源加载前**注册（正常模组初始化都在这个窗口内）；
	 * 晚注册的物品要等一次资源重载（F3+T / {@code /reload}）才换上分层模型。</p>
	 */
	private static void ensureClientHook() {
		try {
			if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
				!= net.fabricmc.api.EnvType.CLIENT) {
				return;
			}
			ChasmItemLayerModels.ensureInstalled();
		} catch (Throwable t) {
			// 模型插件没挂上 = 外观退回单张贴图；绝不能让注册物品这件事失败。
			//
			// try 必须罩住**整段**（2026-09-20 修）：没有 Fabric 启动器时
			// FabricLoader.getInstance() 拿到的是"半初始化"的实例，它的 getEnvironmentType()
			// 会抛 NPE（FabricLauncherBase.getLauncher() 为 null）—— 旧写法把这一句留在 try 之外，
			// 于是"在没有 Fabric 环境的 JVM 里注册外观来源"直接 NPE。
			api.chasm.log.ChasmLogger.warn("chasm",
				"物品外观分层的模型插件注册失败（外观退回单张贴图）: {}", t == null ? "null" : t.toString());
		}
	}

	/** 查某物品的图层来源（未注册返回 {@code null} —— 调用方必须处理）。 */
	public static ItemLayerProvider providerOf(Item item) {
		return item == null ? null : PROVIDERS.get(item);
	}

	public static boolean hasProvider(Item item) {
		return providerOf(item) != null;
	}

	/**
	 * 按栈取图层（**永不抛异常、永不返回 null**）。
	 *
	 * <p>实现方抛异常时按"没有图层"处理并打印一次告警：外观出错最多是这个物品长得不对，
	 * 不该让渲染线程崩掉。</p>
	 */
	public static List<ItemLayer> layersOf(ItemStack stack) {
		return layersOf(stack, "");
	}

	/**
	 * **按帧取图层**（{@link ItemLayerProvider#layersOf(ItemStack, String)} 的安全包装）。
	 *
	 * <p>帧为空串（{@code null} 也按空串）时与 {@link #layersOf(ItemStack)} 完全等价：
	 * 没实现帧语义的 provider 会走默认方法直接转发，所以**其它物品一点不受影响**。</p>
	 *
	 * @param frame 当前动画帧/状态名（如 Tetra 弓的 {@code undrawn / draw_0 / draw_1 / draw_2}）；
	 *              {@code ""} = 无帧
	 */
	public static List<ItemLayer> layersOf(ItemStack stack, String frame) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}
		ItemLayerProvider provider = PROVIDERS.get(stack.getItem());
		if (provider == null) {
			return List.of();
		}
		try {
			List<ItemLayer> layers = provider.layersOf(stack, frame == null ? "" : frame);
			return layers == null ? List.of() : layers;
		} catch (RuntimeException e) {
			api.chasm.log.ChasmLogger.error("chasm",
				"物品 {} 的外观图层求值抛异常（按无图层处理，客户端不崩）: {}",
				stack.getItem(), e.toString());
			return List.of();
		}
	}

	/**
	 * **该栈当前的动画帧 / 状态名**（{@link ItemLayerProvider#frameOf} 的安全包装）。
	 *
	 * <p>契约：**永不抛异常、永不返回 null**（拿不到实体、provider 没实现帧、provider 自己抛了
	 * —— 一律返回空串 {@code ""}，等价于"这个物品没有帧"）。O(1)。</p>
	 *
	 * @param stack  物品栈；null / 空栈 → {@code ""}
	 * @param entity 持有者，可为 null（GUI/物品栏里画的东西没有持有者）
	 */
	public static String frameOf(ItemStack stack, net.minecraft.world.entity.LivingEntity entity) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}
		ItemLayerProvider provider = PROVIDERS.get(stack.getItem());
		if (provider == null) {
			return "";
		}
		try {
			String frame = provider.frameOf(stack, entity);
			return frame == null ? "" : frame;
		} catch (RuntimeException e) {
			api.chasm.log.ChasmLogger.error("chasm",
				"物品 {} 的外观帧求值抛异常（按无帧处理，客户端不崩）: {}",
				stack.getItem(), e.toString());
			return "";
		}
	}

	/**
	 * **该栈当前状态的模型姿态变换**（{@link ItemLayerProvider#displayOf} 的安全包装）。
	 *
	 * <p>契约与 {@link #frameOf} 一致：**永不抛异常**；provider 没实现（默认实现）或 provider 自己抛了
	 * → 返回 {@code null}，也就是"这个物品不表态"，渲染侧原样沿用被包模型的 {@code getTransforms()}。
	 * 于是**没有实现这个通道的物品，姿态与从前逐字一致**。</p>
	 *
	 * <p>调用点在模型构建路径上（每个"没命中缓存的栈 + 帧"一次），不是逐帧热路径；
	 * 但实现仍应 O(1)、不分配。</p>
	 *
	 * @param stack 物品栈；null / 空栈 → {@code null}
	 * @param frame 当前帧/状态名（与 {@link #frameOf} 同源）；null 按空串
	 */
	public static ItemDisplay displayOf(ItemStack stack, String frame) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		ItemLayerProvider provider = PROVIDERS.get(stack.getItem());
		if (provider == null) {
			return null;
		}
		try {
			return provider.displayOf(stack, frame == null ? "" : frame);
		} catch (RuntimeException e) {
			api.chasm.log.ChasmLogger.error("chasm",
				"物品 {} 的 display 变换求值抛异常（按不表态处理，沿用原模型姿态）: {}",
				stack.getItem(), e.toString());
			return null;
		}
	}

	/** 已注册的物品数（调试用）。 */
	public static int size() {
		return PROVIDERS.size();
	}
}
