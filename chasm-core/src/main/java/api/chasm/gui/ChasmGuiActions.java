package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命名按钮动作注册表：给"被 JSON（或数据驱动）界面引用的按钮"提供一个可被全局反查的
 * handler（服务端主线程安全执行）。
 *
 * <p>数据驱动的界面（例如由另一代理从 JSON 加载的 {@link ChasmGui}）在构建按钮时无法直接
 * 绑定到某个模组类的 lambda，因此先用 {@link #register(String, String, ChasmButtonHandler)}
 * 注册一个命名的动作，再让 {@link ChasmGuiBuilder#buttonAction(ResourceLocation, String, int, int)}
 * 通过动作 id（{@code modId:name}）反查 handler 绑定到按钮上。</p>
 *
 * <p>线程安全、同名注册直接覆盖。所有回调仍须在服务端主线程执行（点击分发保证）。</p>
 */
public final class ChasmGuiActions {

	private static final Map<ResourceLocation, ChasmButtonHandler> ACTIONS = new ConcurrentHashMap<>();

	private ChasmGuiActions() {
	}

	/**
	 * 注册一个命名的按钮动作（服务端主线程安全执行）。同名覆盖。
	 *
	 * @param modId   命名空间（通常是注册该动作的模组 id）
	 * @param name    动作名（path）
	 * @param handler 点击回调，不可为空
	 */
	public static void register(String modId, String name, ChasmButtonHandler handler) {
		if (handler == null) {
			throw new IllegalArgumentException("handler 不能为空");
		}
		ACTIONS.put(ResourceLocation.fromNamespaceAndPath(modId, name), handler);
	}

	/**
	 * 按完整 id 反查动作；未注册返回 null。
	 *
	 * @param id {@code modId:name}，可为 null
	 */
	public static ChasmButtonHandler get(ResourceLocation id) {
		return id == null ? null : ACTIONS.get(id);
	}

	/**
	 * 按 id 反查动作（便捷）；未注册返回 null。
	 *
	 * @param modId 命名空间
	 * @param name  动作名（path）
	 */
	public static ChasmButtonHandler get(String modId, String name) {
		return ACTIONS.get(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/** 全部已注册动作数。 */
	public static int size() {
		return ACTIONS.size();
	}
}