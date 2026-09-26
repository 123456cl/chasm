package api.chasm.gui;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控件种类注册表（开放）：内置 4 种，第三方可注册任意新种类。
 *
 * <pre>{@code
 * // 注册一种"液体槽"控件（服务端元数据）
 * ChasmWidgetKinds.register(ChasmWidgetKind.display(id("mymod", "tank")));
 * // 客户端注册它的渲染器
 * ChasmWidgetRenderers.register(id("mymod", "tank"), (g, menu, spec, x, y, w, h, hovered, focused, partial) -> {
 *     ChasmGuiRenderer.drawBar(g, theme, x, y, w, h, menu.smoothData(spec.key()) / 100.0);
 * });
 * }</pre>
 */
public final class ChasmWidgetKinds {

	/** 滑块：可拖动/方向键调整，值会回写绑定的整数数据键或自定义处理器。 */
	public static final ChasmWidgetKind SLIDER =
		ChasmWidgetKind.interactive(ResourceLocation.fromNamespaceAndPath("chasm", "slider"));
	/** 进度/能量条：只读展示，直接读取绑定的整数数据键（含客户端平滑值）。 */
	public static final ChasmWidgetKind BAR =
		ChasmWidgetKind.display(ResourceLocation.fromNamespaceAndPath("chasm", "bar"));
	/** 开关：0/1 写入绑定的整数数据键。 */
	/** **声明式节点控件**：整块界面由"状态 → 节点列表"派生，点击值 = 节点序号。 */
	public static final ChasmWidgetKind DECL =
		new ChasmWidgetKind(ResourceLocation.fromNamespaceAndPath("chasm", "decl"), true, false,
			ChasmWidgetKind.ClickAxis.NODE);

	/** **列表控件**（按行命中；值 = 行号）。 */
	public static final ChasmWidgetKind LIST =
		ChasmWidgetKind.list(ResourceLocation.fromNamespaceAndPath("chasm", "list"));

	public static final ChasmWidgetKind TOGGLE =
		ChasmWidgetKind.interactive(ResourceLocation.fromNamespaceAndPath("chasm", "toggle"));
	/** 按钮（与旧 {@code .button(...)} 等价的控件形态，供第三方/未来统一使用）。 */
	public static final ChasmWidgetKind BUTTON =
		ChasmWidgetKind.interactive(ResourceLocation.fromNamespaceAndPath("chasm", "button"));

	private static final Map<ResourceLocation, ChasmWidgetKind> REGISTRY = new ConcurrentHashMap<>();

	static {
		register(SLIDER);
		register(BAR);
		register(TOGGLE);
		register(BUTTON);
	}

	private ChasmWidgetKinds() {
	}

	/** 注册控件种类（幂等覆盖）。 */
	public static ChasmWidgetKind register(ChasmWidgetKind kind) {
		if (kind == null || kind.id() == null) {
			throw new IllegalArgumentException("控件种类及其 id 不可为 null");
		}
		ChasmWidgetKind old = REGISTRY.put(kind.id(), kind);
		if (old != null && old != kind) {
			ChasmLogger.debug("chasm", "控件种类 {} 被重复注册（已覆盖）", kind.id());
		}
		return kind;
	}

	/** 按 id 查询（未注册返回 null）。 */
	public static ChasmWidgetKind get(ResourceLocation id) {
		return id == null ? null : REGISTRY.get(id);
	}

	/** 是否已注册。 */
	public static boolean isRegistered(ResourceLocation id) {
		return get(id) != null;
	}

	/** 全部已注册种类（只读）。 */
	public static Collection<ChasmWidgetKind> all() {
		return Collections.unmodifiableCollection(REGISTRY.values());
	}

	public static int size() {
		return REGISTRY.size();
	}
}