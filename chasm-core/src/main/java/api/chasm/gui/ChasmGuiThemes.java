package api.chasm.gui;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主题注册表（开放）：模组/整合包可注册自己的 {@link ChasmGuiTheme}，界面用
 * {@link ChasmGuiBuilder#theme(ResourceLocation)} 引用。
 *
 * <p>解析规则：界面未指定主题 → {@link ChasmGuiTheme#DEFAULT}；指定了但未注册 → 回落默认并记一次 WARN
 * （**绝不因为主题缺失而显示异常**）。</p>
 */
public final class ChasmGuiThemes {

	private static final Map<ResourceLocation, ChasmGuiTheme> REGISTRY = new ConcurrentHashMap<>();

	static {
		register(ChasmGuiTheme.DEFAULT);
		register(ChasmGuiTheme.DARK);
	}

	private ChasmGuiThemes() {
	}

	/** 注册主题（幂等覆盖，便于开发期热改）。 */
	public static ChasmGuiTheme register(ChasmGuiTheme theme) {
		if (theme == null || theme.id() == null) {
			throw new IllegalArgumentException("主题及其 id 不可为 null");
		}
		ChasmGuiTheme old = REGISTRY.put(theme.id(), theme);
		if (old != null && old != theme) {
			ChasmLogger.debug("chasm", "主题 {} 被重复注册（已覆盖）", theme.id());
		}
		return theme;
	}

	/** 按 id 查询（未注册返回 null）。 */
	public static ChasmGuiTheme get(ResourceLocation id) {
		return id == null ? null : REGISTRY.get(id);
	}

	/** 解析：null / 未注册 → 默认主题。 */
	public static ChasmGuiTheme resolve(ResourceLocation id) {
		if (id == null) {
			return ChasmGuiTheme.DEFAULT;
		}
		ChasmGuiTheme theme = REGISTRY.get(id);
		if (theme == null) {
			ChasmLogger.debug("chasm", "界面主题 {} 未注册，回落默认主题", id);
			return ChasmGuiTheme.DEFAULT;
		}
		return theme;
	}

	/** 已注册主题（只读）。 */
	public static Collection<ChasmGuiTheme> all() {
		return Collections.unmodifiableCollection(REGISTRY.values());
	}

	public static int size() {
		return REGISTRY.size();
	}
}
