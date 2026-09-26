package api.chasm.gui.client;

import api.chasm.gui.ChasmGuiTheme;
import api.chasm.gui.ChasmGuiThemes;
import api.chasm.gui.ChasmMenu;
import api.chasm.gui.ChasmWidgetKinds;
import api.chasm.gui.ChasmWidgetSpec;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控件渲染器注册表（**开放**）：控件种类 → 客户端画法。
 *
 * <p>加一种新控件 = 服务端 {@code ChasmWidgetKinds.register(...)} 声明元数据 + 这里注册画法，
 * **不需要改框架任何代码**（液体槽、滚动列表、标签页、图表都可以这样加）。</p>
 *
 * <p>内置三种：滑块、进度条、开关；未注册渲染器的种类会退化为"主题底 + 文字"的安全画法。</p>
 */
@Environment(EnvType.CLIENT)
public final class ChasmWidgetRenderers {

	/** 控件画法。坐标已换算成屏幕绝对坐标（含面板偏移）。 */
	@FunctionalInterface
	public interface Renderer {
		void render(GuiGraphics g, ChasmMenu menu, ChasmWidgetSpec spec, int x, int y, int w, int h,
					boolean hovered, boolean focused, float partialTick, int mouseX, int mouseY);
	}

	private static final Map<ResourceLocation, Renderer> RENDERERS = new ConcurrentHashMap<>();

	private ChasmWidgetRenderers() {
	}

	/** 注册控件画法（幂等覆盖）。 */
	public static void register(ResourceLocation kind, Renderer renderer) {
		if (kind == null || renderer == null) {
			throw new IllegalArgumentException("控件种类与渲染器不可为 null");
		}
		RENDERERS.put(kind, renderer);
	}

	/** 取画法（未注册返回 null → 调用方用安全兜底画法）。 */
	public static Renderer get(ResourceLocation kind) {
		return kind == null ? null : RENDERERS.get(kind);
	}

	/** 注册内置三种控件的画法（客户端初始化时调用一次）。 */
	public static void registerBuiltins() {
		register(ChasmWidgetKinds.SLIDER.id(), ChasmWidgetRenderers::renderSlider);
		register(ChasmWidgetKinds.BAR.id(), ChasmWidgetRenderers::renderBar);
		register(ChasmWidgetKinds.TOGGLE.id(), ChasmWidgetRenderers::renderToggle);
		register(ChasmWidgetKinds.LIST.id(), ChasmWidgetRenderers::renderList);
		// 声明式节点：**不在这里画**。节点由 ChasmScreen 分两层绘制（物品之前 / 物品之后），
		// 这个控件只承担"整面板可点"的命中角色；在这里再画一次会变成重复绘制且层级错乱。
		register(ChasmWidgetKinds.DECL.id(), (g, menu, spec, x, y, w2, h2, hovered, focused, partial, mx, my) -> { });
	}

	/** 当前值（整数数据键优先，其次是控件初值）。 */
	public static int valueOf(ChasmMenu menu, ChasmWidgetSpec spec) {
		return menu.hasData(spec.key()) ? menu.data(spec.key()) : spec.initial();
	}

	/** 平滑值（进度/滑块动画用；非数据键时退化为当前值）。 */
	public static double smoothValueOf(ChasmMenu menu, ChasmWidgetSpec spec) {
		return menu.hasData(spec.key()) ? menu.smoothData(spec.key()) : spec.initial();
	}

	/** **列表控件**的内置画法（第三方可覆盖成自己的行样式：图标、描述、材料…）。 */
	private static void renderList(GuiGraphics g, ChasmMenu menu, ChasmWidgetSpec spec, int x, int y, int w, int h,
								   boolean hovered, boolean focused, float partialTick, int mouseX, int mouseY) {
		g.fill(x, y, x + w, y + h, 0x88000000);
		Minecraft mc = Minecraft.getInstance();
		int rowHeight = 12;
		int rows = Math.max(1, h / rowHeight);
		int selected = valueOf(menu, spec);
		for (int row = 0; row < rows; row++) {
			int index = row + scrollOf(menu, spec);
			int rowY = y + row * rowHeight;
			if (index == selected) {
				g.fill(x + 1, rowY, x + w - 1, rowY + rowHeight - 1, 0x60FFFFFF);
			}
			g.drawString(mc.font, "#" + index, x + 3, rowY + 2, 0xFFCCCCCC);
		}
	}

	/**
	 * **列表行几何**：渲染器声明"我的行从哪里开始、多高、有几行"，
	 * 点击判定用**同一套几何**换算行号 —— 这样就不会再出现"看到的是第 1 行、点到的是第 2 行"。
	 *
	 * <p>踩过的坑：以前点击按"整个控件高度"折算比例，而渲染器往往在顶部留标题，
	 * 于是选中框整体上飘一行（用户实测：点"剑刃"选中了"护手"）。</p>
	 */
	private static final Map<String, int[]> ROW_LAYOUT = new ConcurrentHashMap<>();

	/** 渲染器在绘制时声明行几何（offsetPx 顶部留白、rowHeightPx 行高、rowCount 行数）。 */
	public static void setRowLayout(String key, int offsetPx, int rowHeightPx, int rowCount) {
		ROW_LAYOUT.put(key, new int[] { offsetPx, Math.max(1, rowHeightPx), rowCount });
	}

	/** 鼠标相对控件顶部的 Y → 行号（越界返回 -1）。几何未声明时按整体高度均匀分行。 */
	public static int rowIndexAt(ChasmWidgetSpec spec, int relY) {
		int[] layout = ROW_LAYOUT.get(spec.key());
		if (layout != null) {
			int local = relY - layout[0];
			if (local < 0) {
				return -1;
			}
			int index = local / layout[1];
			return index < layout[2] ? index : -1;
		}
		int height = Math.max(1, spec.pixelHeight());
		int rows = Math.max(1, spec.max() - spec.min() + 1);
		int index = relY * rows / height;
		return index >= 0 && index < rows ? spec.min() + index : -1;
	}

	/** 列表滚动偏移（客户端本地视图状态；GUI 声明 {@code <key>_scroll} 数据键时才生效）。 */
	public static int scrollOf(ChasmMenu menu, ChasmWidgetSpec spec) {
		String key = spec.key() + "_scroll";
		return menu.hasData(key) ? menu.data(key) : 0;
	}

	/**
	 * **平滑滚动偏移**（带小数的行偏移）：列表滚起来不像"跳一格"，而是滑过去。
	 * 渲染时用返回值乘行高即可（例如 {@code y + (row - scroll) * rowHeight}）。
	 */
	public static double smoothScrollOf(ChasmMenu menu, ChasmWidgetSpec spec, String guiId) {
		int target = scrollOf(menu, spec);
		return ChasmGuiAnimations.smoothScroll(guiId + ":" + spec.key(), target, 14.0);
	}

	// ---------------------------------------------------------- 内置画法

	private static void renderSlider(GuiGraphics g, ChasmMenu menu, ChasmWidgetSpec spec, int x, int y, int w, int h,
									  boolean hovered, boolean focused, float partialTick, int mouseX, int mouseY) {
		ChasmGuiTheme theme = themeOf(menu);
		int trackH = Math.max(4, h / 2);
		int trackY = y + (h - trackH) / 2;
		ChasmGuiRenderer.draw(g, theme.sliderTrack(), x, trackY, w, trackH);
		double fraction = spec.fractionOf(valueOf(menu, spec));
		int handleW = 6;
		int handleX = x + (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * (w - handleW));
		ChasmGuiRenderer.draw(g, theme.sliderHandle(), handleX, y, handleW, h);
		drawLabel(g, spec.label() + " " + valueOf(menu, spec), x, y - 1, w);
	}

	private static void renderBar(GuiGraphics g, ChasmMenu menu, ChasmWidgetSpec spec, int x, int y, int w, int h,
								  boolean hovered, boolean focused, float partialTick, int mouseX, int mouseY) {
		ChasmGuiTheme theme = themeOf(menu);
		double ratio = spec.fractionOf((int) Math.round(smoothValueOf(menu, spec)));
		ChasmGuiRenderer.drawBar(g, theme, x, y, w, h, ratio);
		drawLabel(g, spec.label() + " " + (int) Math.round(smoothValueOf(menu, spec)), x + 2, y + h / 2 - 4, w - 4);
	}

	private static void renderToggle(GuiGraphics g, ChasmMenu menu, ChasmWidgetSpec spec, int x, int y, int w, int h,
									 boolean hovered, boolean focused, float partialTick, int mouseX, int mouseY) {
		ChasmGuiTheme theme = themeOf(menu);
		boolean on = valueOf(menu, spec) > 0;
		ChasmGuiRenderer.drawButton(g, theme, x, y, Math.min(w, 16), hovered || on, false);
		drawLabel(g, spec.label() + (on ? " 开" : " 关"), x + 18, y + 4, w - 18);
	}

	/** 兜底：未注册渲染器的种类画成"主题按钮底 + 标签"，保证永远不会空白/崩溃。 */
	static void renderFallback(GuiGraphics g, ChasmMenu menu, ChasmWidgetSpec spec, int x, int y, int w, int h,
							   boolean hovered, boolean focused, float partialTick, int mouseX, int mouseY) {
		ChasmGuiTheme theme = themeOf(menu);
		ChasmGuiRenderer.drawButton(g, theme, x, y, Math.min(h, 16), hovered, false);
		drawLabel(g, spec.label(), x + 18, y + 4, w - 18);
	}

	/** 标签：按背景自动选可读色 + 超宽时缩放（避免文字糊成一团）。 */ 
	private static void drawLabel(GuiGraphics g, String text, int x, int y, int width) {
		drawLabel(g, text, x, y, width, 0xFF24384A);
	}

	private static void drawLabel(GuiGraphics g, String text, int x, int y, int width, int backgroundArgb) {
		if (text == null || text.isEmpty() || width <= 0) {
			return;
		}
		var font = Minecraft.getInstance().font;
		int color = ChasmGuiTheme.readableTextOn(backgroundArgb);
		int textWidth = font.width(text);
		float scale = Math.min(1.0F, (width - 2.0F) / Math.max(1, textWidth));
		g.pose().pushPose();
		g.pose().translate(x, y + (8.0F - 8.0F * scale) / 2.0F, 0.0F);
		g.pose().scale(scale, scale, 1.0F);
		g.drawString(font, text, 0, 0, color, true);
		g.pose().popPose();
	}

	private static ChasmGuiTheme themeOf(ChasmMenu menu) {
		return ChasmGuiThemes.resolve(menu.gui().themeId());
	}

	/** 无障碍朗读文本（label 看起来是翻译键就用可翻译组件，否则按字面量）。 */
	public static Component narrationFor(ChasmWidgetSpec spec) {
		String key = spec.narrationOrDefault();
		return key.indexOf(46) >= 0 ? Component.translatable(key) : Component.literal(key);
	}
}