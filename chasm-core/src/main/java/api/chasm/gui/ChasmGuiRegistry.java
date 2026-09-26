package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 声明式界面注册表：以 {@code ResourceLocation (modId:name)} 为键，维护全部已注册界面。
 *
 * <p>客户端屏幕绑定（{@code MenuScreens}）与服务端按钮点击回调都从这里按 id 反查界面。
 * 隔离边界沿用 {@link ChasmGuiBuilder} 传入的 {@code modId}——不同模组的界面天然不冲突。</p>
 */
public final class ChasmGuiRegistry {

	private static final Map<ResourceLocation, ChasmGui> GUIS = new LinkedHashMap<>();

	private ChasmGuiRegistry() {
	}

	/** 登记一个界面（由 {@link ChasmGuiBuilder#register()} 调用）。 */
	static void register(ChasmGui gui) {
		GUIS.put(gui.id(), gui);
	}

	/** 按 id 查询界面；不存在返回 null。 */
	public static ChasmGui get(ResourceLocation id) {
		return GUIS.get(id);
	}

	/** 全部已登记界面（只读视图）。 */
	public static Collection<ChasmGui> all() {
		return Collections.unmodifiableCollection(GUIS.values());
	}

	/** 已登记界面数量。 */
	public static int size() {
		return GUIS.size();
	}
}