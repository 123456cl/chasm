package api.chasm.key;

import api.chasm.item.ChasmKeyBinding;
import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chasm 自定义按键注册表（第十二步）。
 *
 * <p>把「按键从左右键扩展到任意自定义键」落到框架：模组作者在入口静态处声明
 * {@code Chasm.keys().register(modId, name, keyCode, displayName)}，得到一个
 * {@link ChasmKeyBinding} 句柄，随后可通过 {@code ItemBuilder.onKeyPress} 绑定到某个物品。
 * 注册的动作是<b>共享环境安全</b>的（不触碰任何客户端类），真正的 {@code KeyMapping}
 * 创建由客户端初始化器完成。</p>
 *
 * <pre>{@code
 * public static final ChasmKeyBinding MAGIC_KEY =
 *     Chasm.keys().register("mymod", "magic_blast", 79, "Magic Blast"); // 79 = GLFW O
 *
 * @Register("wand")
 * public static final Item WAND = Chasm.item()
 *     .onKeyPress(MAGIC_KEY, ctx -> {  // 手持 WAND 按 O 触发
 *         ctx.player().sendSystemMessage(Component.literal("按 O 发射魔法弹！"));
 *     })
 *     .register();
 * }</pre>
 */
public final class ChasmKeys {

	/** 门面实例（配合 {@code Chasm.keys()} 使用）。 */
	public static final ChasmKeys INSTANCE = new ChasmKeys();

	/** 按键 id → 句柄。 */
	private final Map<ResourceLocation, ChasmKeyBinding> bindings = new ConcurrentHashMap<>();

	private ChasmKeys() {
	}

	/**
	 * 注册一个自定义按键。
	 *
	 * @param modId       模组命名空间（决定按键 id：{@code modId:name}）
	 * @param name        按键注册名（不含命名空间）
	 * @param keyCode     GLFW 键码（例如 O=79、L=76；可用 {@code org.lwjgl.glfw.GLFW.GLFW_KEY_X}
	 *                    的整数值，但在共享/服务端环境请直接传字面量以规避 GLFW 类加载）
	 * @param displayName 按键可见名（自动出现在原版按键控制列表，DataGen 自动生成翻译）
	 * @return 按键句柄（可绑定到物品）
	 */
	public ChasmKeyBinding register(String modId, String name, int keyCode, String displayName) {
		ChasmKeyBinding binding = new ChasmKeyBinding(
			ResourceLocation.fromNamespaceAndPath(modId, name), displayName, keyCode);
		bindings.put(binding.id(), binding);
		ChasmLogger.info(modId, "注册自定义按键 {} (keyCode={}, 显示名={})", binding.id(), keyCode, displayName);
		return binding;
	}

	/** 注册一个自定义按键（显示名默认用注册名）。 */
	public ChasmKeyBinding register(String modId, String name, int keyCode) {
		return register(modId, name, keyCode, name);
	}

	/** 按 id 查询已注册的按键。 */
	public Optional<ChasmKeyBinding> get(ResourceLocation id) {
		return Optional.ofNullable(bindings.get(id));
	}

	/** 所有已注册按键（只读视图）。 */
	public Collection<ChasmKeyBinding> all() {
		return Collections.unmodifiableCollection(bindings.values());
	}

	/** 已注册按键数量（调试/日志）。 */
	public int size() {
		return bindings.size();
	}
}