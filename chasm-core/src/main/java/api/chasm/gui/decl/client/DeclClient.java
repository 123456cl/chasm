package api.chasm.gui.decl.client;

import api.chasm.gui.ChasmMenu;
import api.chasm.gui.GuiRect;
import api.chasm.gui.client.ChasmGuiAnimations;
import api.chasm.gui.decl.GuiDeclarations;
import api.chasm.gui.decl.GuiNode;
import api.chasm.gui.decl.GuiProbe;
import api.chasm.gui.decl.OutlinedText;
import api.chasm.gui.decl.GuiSource;
import api.chasm.gui.decl.GuiState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声明式界面的客户端：**构建 → 绘制 → 命中，用的是同一份节点列表、同一个坐标空间**。
 *
 * <p>这就是"所见即所点"的结构性保证：不存在"某处画了、另一处忘了判断"的余地。</p>
 *
 * <h2>动画（第 3 步：节点键过渡）</h2>
 * <p>节点按 {@code key} 记忆自己的显示位置与出现进度：</p>
 * <ul>
 *   <li><b>位置</b>用指数逼近 —— 同一个 key 这帧在 A、下帧要求在 B，它就滑过去（Tetra 的列表就是这样）。</li>
 *   <li><b>出现进度</b>用入场动画 —— 新 key 从透明浮现；**曾经消失又回来的 key 会重播**，
 *       因为每帧都比对"上一帧存在过的 key 集合"，消失的 key 会被清掉动画状态。</li>
 * </ul>
 * <p>这两件事都不需要节点自己维护生命周期：{@code ui = f(state)} 依然是纯函数。</p>
 */
public final class DeclClient {

	private DeclClient() {
	}

	/** 每个界面上一帧存在过的节点 key（用于"消失又回来"时重播入场动画）。 */
	private static final Map<ResourceLocation, Set<String>> PRESENCE = new ConcurrentHashMap<>();
	/** 每个界面上一帧"节点 key → 脉冲 tag"，tag 变化即触发一次一次性动画（真值来自 state，不是记忆）。 */
	private static final Map<ResourceLocation, Map<String, String>> TAGS = new ConcurrentHashMap<>();
	/**
	 * **帧内缓存**：渲染一帧要取两次节点列表（物品之前那层 + 物品之后那层），命中即复用同一份。
	 *
	 * <p>为什么必须有：节点列表由状态派生（{@code ui = f(state)}），构建成本随内容量增长 ——
	 * 现在的规模（几十个节点）无所谓，但蓝图/变体全量导入后，一次列表页构建要过滤几百份蓝图、
	 * 每帧构建两次就会变成"每帧成本随内容线性增长"。缓存后每帧只构建一次。</p>
	 *
	 * <p>生命周期：{@link #startRenderFrame} 清空（新的一帧）→ 渲染期间复用 → {@link #endRenderFrame}
	 * 释放（**不跨帧持有**，避免状态陈旧）。</p>
	 */
	private static final Map<ResourceLocation, List<GuiNode>> FRAME_CACHE = new ConcurrentHashMap<>();

	/** 已报告过的构建异常（key = 界面 + 异常类型 + 消息）：同一种错只报一次，别每帧刷屏。 */
	private static final java.util.Set<String> REPORTED_BUILD_FAILURES = ConcurrentHashMap.newKeySet();

	/**
	 * 界面构建失败时是否直接把异常抛出去（定位 bug 时用）。
	 *
	 * <p>默认 {@code false}：{@code source.build} 跑在**渲染线程**上，任何空指针都会顺着
	 * {@code GameRenderer.render} 冒到顶层 —— 玩家看到的是**整个客户端崩溃退出**。
	 * 界面数据出错不该有这种代价，所以默认把它拦在界面边界内。</p>
	 */
	public static volatile boolean strictBuildFailures = false;

	/**
	 * **按键状态来源**（渲染时求值，默认读真实键盘）。
	 *
	 * <p>真机是 {@code GuiStatBar.getTooltipLines:221-229} 的
	 * {@code Screen.hasShiftDown()} —— 每帧渲染 tooltip 的那一刻现问一次，不缓存。
	 * 这里把"怎么问"抽成一个可注入的接口：游戏里用默认实现（读 {@link Screen}），
	 * 离线单测换成假实现就能逐字断言 shift 展开分支，**不需要开窗口**。</p>
	 */
	@FunctionalInterface
	public interface KeyState {

		/** 该键此刻是否按下。 */
		boolean isDown(String key);
	}

	/** 默认按键状态：只有 {@link GuiNode#SHIFT_KEY} 有真实来源，其余键恒 false。 */
	public static volatile KeyState keyState = DeclClient::defaultKeyDown;

	private static boolean defaultKeyDown(String key) {
		if (!GuiNode.SHIFT_KEY.equals(key)) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		// 无窗口环境（单测 / 数据生成）不崩：没有窗口 = 没有按键按下
		return mc != null && mc.getWindow() != null && Screen.hasShiftDown();
	}

	/**
	 * **鼠标指针位置**（GUI 像素空间 —— 与 {@code Screen.render} 收到的 {@code mouseX/mouseY}
	 * 同一个空间；面板像素 = 本值 − 面板原点）。
	 *
	 * @param x       鼠标 x（GUI 像素）
	 * @param y       鼠标 y（GUI 像素）
	 * @param present 是否真的有指针；{@code false} 时 x/y 无意义
	 */
	public record Pointer(double x, double y, boolean present) {

		/**
		 * **哨兵：不在任何节点上**（无窗口 / 单测 / 数据生成环境）。
		 *
		 * <p>它和任何矩形都不相交 —— 悬停判定遇到它一律返回 false，所以离线环境里
		 * "悬停淡入"的默认结果就是"不悬停"，不会误报也不会崩。</p>
		 */
		public static final Pointer NOWHERE = new Pointer(Double.NaN, Double.NaN, false);
	}

	/**
	 * **指针状态来源**（渲染时求值，默认读真实鼠标）。
	 *
	 * <p>和 {@link KeyState} 同一个风格：游戏里用默认实现（读 {@link Minecraft} 的
	 * {@code mouseHandler} 并换算到 GUI 像素），离线单测注入假实现就能逐字断言
	 * "鼠标在节点矩形内 → opacity 变 1、移出 → 变回"，**不需要开窗口**。</p>
	 */
	@FunctionalInterface
	public interface PointerState {

		/** 此刻的鼠标指针（无窗口时返回 {@link Pointer#NOWHERE}）。 */
		Pointer at();
	}

	/** 默认指针状态：从当前窗口的鼠标位置取，换算公式与真实界面（mutil 的 GuiRoot）一致。 */
	public static volatile PointerState pointerState = DeclClient::defaultPointer;

	private static Pointer defaultPointer() {
		Minecraft mc = Minecraft.getInstance();
		// 无窗口环境（单测 / 数据生成）不崩：没有窗口 = 指针不在任何节点上
		if (mc == null || mc.getWindow() == null) {
			return Pointer.NOWHERE;
		}
		var window = mc.getWindow();
		if (window.getScreenWidth() <= 0 || window.getScreenHeight() <= 0) {
			return Pointer.NOWHERE;
		}
		// 与 mutil GuiRoot.java:23-24 同一换算：原始窗口像素 → GUI 像素（= Screen.render 的 mouseX/Y）
		double x = mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
		double y = mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
		return new Pointer(x, y, true);
	}

	/** 当前的指针（默认读真实鼠标；没有窗口时 = {@link Pointer#NOWHERE}）。 */
	public static Pointer pointer() {
		PointerState source = pointerState;
		Pointer value = source == null ? null : source.at();
		return value == null ? Pointer.NOWHERE : value;
	}

	/**
	 * **节点的悬停矩形**（面板像素空间）。
	 *
	 * <p>声明了 {@link GuiNode#hoverRegionKey()} 时取那个 key 的节点矩形（真机页签的悬停判定挂在
	 * 按钮本体上，见 {@link GuiNode#opacityWhenHovered(float, String)}）；键不存在或没声明时
	 * 就是节点自己的 {@link GuiNode#rect(ChasmMenu)}。</p>
	 */
	public static GuiRect hoverRegion(GuiNode node, List<GuiNode> nodes, ChasmMenu menu) {
		if (node == null) {
			return null;
		}
		String key = node.hoverRegionKey();
		if (key != null && nodes != null) {
			for (GuiNode other : nodes) {
				if (key.equals(other.key())) {
					return other.rect(menu);
				}
			}
		}
		return node.rect(menu);
	}

	/** 指针是否落在节点的悬停矩形内（用注入的 {@link #pointerState}；离线断言走这里）。 */
	public static boolean hovered(GuiNode node, List<GuiNode> nodes, ChasmMenu menu, int left, int top) {
		return hovered(node, nodes, menu, left, top, pointer());
	}

	/** 指针是否落在节点的悬停矩形内（显式给指针；渲染路径与离线断言共用同一套判定）。 */
	public static boolean hovered(GuiNode node, List<GuiNode> nodes, ChasmMenu menu, int left, int top,
		Pointer pointer) {
		if (pointer == null || !pointer.present()) {
			return false;   // 哨兵：不在任何节点上
		}
		GuiRect region = hoverRegion(node, nodes, menu);
		return region != null && region.contains(pointer.x() - left, pointer.y() - top);
	}

	/** 用当帧鼠标坐标判定悬停（{@link #render} 走这条；换算只有"减面板原点"一处）。 */
	public static boolean hoveredAt(GuiNode node, List<GuiNode> nodes, ChasmMenu menu, int left, int top,
		double mouseX, double mouseY) {
		GuiRect region = hoverRegion(node, nodes, menu);
		return region != null && region.contains(mouseX - left, mouseY - top);
	}

	/**
	 * **这一帧该节点要显示的 tooltip** —— 按键条件在**这里**求值
	 * （真机 {@code GuiStatBar.getTooltipLines:223} 的 {@code Screen.hasShiftDown()}）。
	 *
	 * <p>{@link #render} 走的就是它，所以 {@link GuiNode#tooltipWhen(String, String)}
	 * 声明的展开版永远不会被缓存进节点、也不会跨帧残留。</p>
	 */
	public static String tooltipText(GuiNode node) {
		return node == null ? null : node.tooltipAt(keyState::isDown);
	}

	/** 构建节点列表（面板像素坐标）；同一帧内重复调用会命中帧缓存。 */
	public static List<GuiNode> nodes(ResourceLocation guiId, ChasmMenu menu) {
		List<GuiNode> cached = FRAME_CACHE.get(guiId);
		if (cached != null) {
			return cached;
		}
		// **签名缓存**：签名相同就复用上一帧的节点列表（静止画面下每帧 O(1)、零分配）。
		// 最大年龄兜底：即使某个依赖没进签名（数据包重载 / 语言切换 / 玩家经验…），
		// 最多 250ms 就会重建一次 —— 不会出现"界面不更新"。
		long now = System.nanoTime();
		long signature = guiId.hashCode() * 31L + (menu == null ? 0L : menu.signature());
		CachedNodes previous = NODE_CACHE.get(guiId);
		if (previous != null && previous.signature() == signature && now - previous.at() < NODE_CACHE_MAX_AGE) {
			FRAME_CACHE.put(guiId, previous.nodes());
			return previous.nodes();
		}
		GuiSource source = GuiDeclarations.sourceOf(guiId);
		List<GuiNode> built = source == null ? List.of() : buildSafely(guiId, source, menu);
		NODE_CACHE.put(guiId, new CachedNodes(signature, built, now));
		FRAME_CACHE.put(guiId, built);
		return built;
	}

	/** 签名缓存的条目（{@link #NODE_CACHE}）。 */
	private record CachedNodes(long signature, List<GuiNode> nodes, long at) {
	}

	/** 签名相同最多复用这么久（纳秒）；之后强制重建一次，兜住"没进签名的依赖"。 */
	private static final long NODE_CACHE_MAX_AGE = 250_000_000L;

	/** 跨帧的节点列表缓存（按界面 id）。 */
	private static final Map<ResourceLocation, CachedNodes> NODE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * **界面数据出错不许崩客户端**（2026-09-18 实测：一个 null key 让客户端崩了两次）。
	 *
	 * <p>异常在这里被拦下：**打印完整堆栈**（ERROR 级，含界面 id），这一帧渲染空节点列表 ——
	 * 玩家只是看到这一屏暂时空掉，而不是掉游戏。这不是"掩盖错误"：日志里是一条带完整堆栈的 ERROR，
	 * 同一种异常只报一次（不刷屏），定位体验和看崩溃报告一样；想让它照旧直接崩，
	 * 打开 {@link #strictBuildFailures} 即可。</p>
	 */
	private static List<GuiNode> buildSafely(ResourceLocation guiId, GuiSource source, ChasmMenu menu) {
		try {
			return source.build(new GuiState(menu));
		} catch (RuntimeException e) {
			if (strictBuildFailures) {
				throw e;
			}
			reportBuildFailure(guiId, e);
			return List.of();
		}
	}

	private static void reportBuildFailure(ResourceLocation guiId, RuntimeException e) {
		String key = guiId + "|" + e.getClass().getName() + "|" + e.getMessage();
		if (!REPORTED_BUILD_FAILURES.add(key)) {
			return;
		}
		java.io.StringWriter stack = new java.io.StringWriter();
		e.printStackTrace(new java.io.PrintWriter(stack));
		api.chasm.log.ChasmLogger.error(guiId.getNamespace(),
			"声明式界面 {} 构建节点时抛异常 → 这一帧渲染空列表（**客户端不崩**）。"
				+ "这是界面数据/代码的 bug，完整堆栈：\n{}", guiId, stack.toString());
	}

	/** 开始新的一帧（由 {@code ChasmScreen.render} 在最前面调用）：丢掉上一帧的缓存。 */
	public static void startRenderFrame(ResourceLocation guiId) {
		FRAME_CACHE.remove(guiId);
	}

	/** 结束这一帧（渲染最后调用）：释放缓存，不跨帧持有。 */
	public static void endRenderFrame(ResourceLocation guiId) {
		FRAME_CACHE.remove(guiId);
	}

	/**
	 * 命中测试：**从后往前**找最上层、且带动作且启用的节点。
	 *
	 * <p>坐标换算只在这里做一次（面板坐标 = 鼠标 - 面板原点）。</p>
	 *
	 * <p><b>命中的是"看到的那个位置"</b>：节点在过渡中可以正在滑动，所以命中矩形用与绘制
	 * 完全相同的平滑位置（{@link ChasmGuiAnimations#current}），而不是它的目标位置 ——
	 * 否则滑动的那两百毫秒里会出现"看得见却点不到"。这正是"所见即所点"要覆盖的边界情况。</p>
	 */
	public static int hitTest(ResourceLocation guiId, ChasmMenu menu, double mouseX, double mouseY,
		int left, int top) {
		List<GuiNode> nodes = nodes(guiId, menu);
		for (int i = nodes.size() - 1; i >= 0; i--) {
			GuiNode node = nodes.get(i);
			if (node.action() == null || !node.isEnabled()) {
				continue;
			}
			GuiRect rect = node.rect(menu);
			String animKey = guiId + ":" + node.key();
			// 与绘制走**同一个**换算（含面板原点与入场位移），所以"看到的"就是"点得到的"
			GuiRect onScreen = animatedScreenRect(left, top, animKey, node, rect);
			int x = onScreen.x();
			int y = onScreen.y();
			if (mouseX >= x && mouseX < x + rect.width() && mouseY >= y && mouseY < y + rect.height()) {
				// **点击坐标**：把它按面板像素空间记下来（真机滑块就是按 mouseX 算段位的，
				// 见 GuiSliderSegmented.calculateSegment:81-83）。服务端派发“带坐标的动作”时读它。
				GuiDeclarations.recordClick(guiId, mouseX - left, mouseY - top);
				return i;
			}
		}
		return -1;
	}

	/**
	 * 每帧开始时对一次"存在性"：上一帧有、这一帧没有的 key，清掉动画状态。
	 *
	 * <p>没有这一步，节点消失再出现会是"啪地出现"而不是滑入 —— 因为入场进度早就到 1 了。</p>
	 */
	public static void beginFrame(ResourceLocation guiId, List<GuiNode> nodes) {
		Set<String> current = new HashSet<>();
		for (GuiNode node : nodes) {
			current.add(node.key());
		}
		Set<String> previous = PRESENCE.put(guiId, current);
		String prefix = guiId + ":";
		if (previous != null) {
			for (String key : previous) {
				if (!current.contains(key)) {
					ChasmGuiAnimations.reset(prefix + key);
					ChasmGuiAnimations.reset(prefix + key + ":x");
					ChasmGuiAnimations.reset(prefix + key + ":y");
				}
			}
		}
		// 脉冲触发：tag 是 state 的一部分，框架只做"前后帧比对"，port 不用记住上一帧
		Map<String, String> tags = new HashMap<>();
		Map<String, String> previousTags = TAGS.put(guiId, tags);
		for (GuiNode node : nodes) {
			if (!node.hasAlphaTimeline()) {
				continue;
			}
			tags.put(node.key(), node.alphaTag());
			String before = previousTags == null ? null : previousTags.get(node.key());
			if (before == null || !before.equals(node.alphaTag())) {
				ChasmGuiAnimations.trigger(prefix + node.key() + ":pulse");
			}
		}
	}

	/**
	 * **节点在屏幕上的矩形**：面板原点 + 面板内位置（含位置过渡的平滑值）。
	 *
	 * <p>这是绘制与命中**唯一**的坐标换算点。加面板原点这一步以前散在两个地方，
	 * 结果加动画时漏掉了一次 —— 表现就是"整个界面朝左上偏了正好一个面板原点，节点全挤在左上角互相压字"。
	 * 现在它只有一个实现，谁都没机会再漏。</p>
	 */
	public static GuiRect animatedScreenRect(int left, int top, String animKey, GuiRect panelRect) {
		return animatedScreenRect(left, top, animKey, null, panelRect);
	}

	/**
	 * 屏幕矩形的完整版：位置过渡（指数平滑）+ **入场线性位移**（Tetra 的"从旁边滑进来"）。
	 *
	 * <p>入场位移也走这里，是为了让**命中跟着看得见的位置**走 —— 少一处换算就少一次"看得见点不到"。</p>
	 */
	public static GuiRect animatedScreenRect(int left, int top, String animKey, GuiNode node,
		GuiRect panelRect) {
		double x = ChasmGuiAnimations.smooth(animKey + ":x", panelRect.x());
		double y = ChasmGuiAnimations.smooth(animKey + ":y", panelRect.y());
		if (node != null && node.hasSlide()) {
			// 线性（与 mutil 一致：无缓动），进度用入场计时器
			double t = ChasmGuiAnimations.entry(animKey + ":slide", node.slideMs() / 1000.0);
			x += (1.0 - t) * node.slideX();
			y += (1.0 - t) * node.slideY();
		}
		return new GuiRect(left + (int) Math.round(x), top + (int) Math.round(y),
			panelRect.width(), panelRect.height());
	}

	/** 节点的**最终透明度乘数**：逐元素透明度 × 一次性脉冲时间轴（未触发时取静止值）。 */
	public static double alphaMultiplier(String animKey, GuiNode node) {
		return alphaMultiplier(animKey, node, false);
	}

	/**
	 * 最终透明度乘数的完整版：**悬停时**逐元素透明度取
	 * {@link GuiNode#opacityAt(boolean)}（真机页签 label 的 onFocus/onBlur 淡入，见该方法的 javadoc）。
	 *
	 * <p>{@code hovered} 由渲染时现算（{@link #hoveredAt}），节点不缓存它。
	 * 不声明 {@link GuiNode#opacityWhenHovered(float)} 的节点，{@code opacityAt} 恒等于
	 * {@code opacity()}，所以 2 参数版本与旧行为**逐字相同**。</p>
	 */
	public static double alphaMultiplier(String animKey, GuiNode node, boolean hovered) {
		double multiplier = node.opacityAt(hovered);
		if (node.hasAlphaTimeline()) {
			multiplier *= ChasmGuiAnimations.timeline(animKey + ":pulse", node.alphaRest(),
				node.alphaDelayMs(), node.alphaDurations(), node.alphaTargets());
		}
		return multiplier;
	}

	/**
	 * 绘制指定层的节点：按列表顺序（后面的在上面）；入场动画按节点 key 复用状态。
	 *
	 * @param layer {@link GuiNode.Layer#BEHIND} 在物品之前画，{@link GuiNode.Layer#ABOVE} 在物品之后画
	 */
	public static void render(GuiGraphics g, ResourceLocation guiId, ChasmMenu menu,
		int left, int top, int mouseX, int mouseY, GuiNode.Layer layer) {
		List<GuiNode> nodes = nodes(guiId, menu);
		if (layer == GuiNode.Layer.BEHIND) {
			beginFrame(guiId, nodes);
			// **UI 探针**（默认关闭）：节点列表**刚刚建好、一个像素都还没画**的时刻 ——
			// 这一刻手里就是"这一帧的界面真相"（状态 + 全部节点）。
			// ENABLED 是 static final 且默认 false → 关掉时这一行是死代码（JIT 直接消掉），
			// 而且探针只读（不碰 ChasmGuiAnimations，那会推进时间戳改变画面）。
			if (GuiProbe.ENABLED) {
				GuiProbe.onDraw(guiId, menu, nodes, left, top);
			}
		}
		Minecraft mc = Minecraft.getInstance();
		String hoveredTooltip = null;
		double relMouseX = mouseX - left;
		double relMouseY = mouseY - top;
		for (GuiNode node : nodes) {
			if (node.layer() != layer) {
				continue;
			}
			GuiRect rect = node.rect(menu);
			String animKey = guiId + ":" + node.key();
			double appear = ChasmGuiAnimations.entry(animKey, 0.18 + node.delay());
			if (appear <= 0.0) {
				continue;
			}
			// **悬停透明度在渲染时求值**：只对声明过 hoverOpacity 的节点算（其余节点走不到这里，
			// 判定与求值都省掉）；悬停矩形可以是另一个节点的（真机页签：悬停按钮本体 → label 淡入）。
			boolean hoveredOpacity = (node.isEnabled() || node.isHoverWhenDisabled()) && node.hasHoverOpacity()
				&& hoveredAt(node, nodes, menu, left, top, mouseX, mouseY);
			if (alphaMultiplier(animKey, node, hoveredOpacity) <= 0.0
				&& node.texture() == null && node.text() == null) {
				continue;   // 脉冲静止值 0 的覆盖层：不可见就不画
			}
			float alpha = (float) (Math.min(1.0, appear) * alphaMultiplier(animKey, node, hoveredOpacity));
			// 位置平滑：key 不变但位置变了 → 滑过去（列表重排/展开就是靠这个）
			// **屏幕矩形 = 面板原点 + 面板内位置**，只走 animatedScreenRect 一处 ——
			// 这里少加一次面板原点，整个界面就会朝左上偏 (leftPos, topPos)，而且所有节点挤在一起互相压字。
			GuiRect onScreen = animatedScreenRect(left, top, animKey, node, rect);
			int x = onScreen.x();
			int y = onScreen.y();
			// **disabled 仍可悬停**（真机 disabled 按钮照样 onFocus + 显示失败原因 tooltip，
			// 见 CraftButtonGui：enabled=false 只是不响应点击）。点击路径（hitTest）仍然只看 enabled。
			boolean hovered = (node.isEnabled() || node.isHoverWhenDisabled()) && rect.contains(relMouseX, relMouseY);
			int tone = hovered && node.hoverColor() != 0 ? node.hoverColor() : 0;
			if (node.isFill()) {
				int argb = node.fillColor();
				int fa = (int) (((argb >>> 24) & 0xFF) * alpha);
				g.fill(x, y, x + node.width(), y + node.height(), (fa << 24) | (argb & 0xFFFFFF));
			}
			if (node.texture() != null) {
				int argb = tone != 0 ? tone : (node.spriteTint() != 0 ? node.spriteTint() : 0xFFFFFFFF);
				float a = alpha * ((argb >>> 24) & 0xFF) / 255.0F;
				float r = ((argb >> 16) & 0xFF) / 255.0F;
				float gg = ((argb >> 8) & 0xFF) / 255.0F;
				float b = (argb & 0xFF) / 255.0F;
				if (node.isScaled()) {
					// 取一块、画成另一个尺寸（16×16 字形 → 15×15 等）。
					// 不能用 innerBlit：1.21.1 里它是包内可见的（mutil 能用是因为 1.20.1 Forge 的映射不同），
					// 所以走 pose 缩放 —— 全是公开 API，效果一样。
					g.pose().pushPose();
					g.pose().translate(x, y, 0.0F);
					g.pose().scale(node.width() / (float) node.srcWidth(),
						node.height() / (float) node.srcHeight(), 1.0F);
					g.setColor(r, gg, b, a);
					g.blit(node.texture(), 0, 0, node.u(), node.v(),
						node.srcWidth(), node.srcHeight(), node.texWidth(), node.texHeight());
					g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
					g.pose().popPose();
				} else {
					g.setColor(r, gg, b, a);
					g.blit(node.texture(), x, y, node.u(), node.v(), node.width(), node.height(),
						node.texWidth(), node.texHeight());
					g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
				}
			}
			if (node.text() != null) {
				int argb = tone != 0 ? tone : node.color();
				// **alpha 语义**（与参考实现 mutil 的 colorWithOpacity 一致）：颜色里**没有 alpha 字节 = 不透明**。
				// 以前写成 (argb>>>24)*alpha，而界面配色基本都是 0xRRGGBB（alpha 字节为 0）→ a 恒 0，
				// 原版 Font.adjustColor 又把 alpha<4 强行改回不透明 → 文字的 opacity / 入场淡入 / 脉冲**全部失效**。
				int baseAlpha = (argb >>> 24) == 0 ? 0xFF : ((argb >>> 24) & 0xFF);
				int a = (int) (baseAlpha * alpha);
				int color = (a << 24) | (argb & 0xFFFFFF);
				// **缩放吸附**：原版字形是预栅格化位图，只能做"整数倍缩小"。
				// 0.7/0.8 这种非整数比例会让 5×7 字形映到 3.5×4.9 像素，采样丢整列/整行 → 缺棱缺角。
				// 参考实现 mutil 的 GuiStringSmall 用的就是 0.5（并把坐标 ×2 保证落点是整数）。
				float scale = node.textScale() >= 0.75F ? 1.0F : 0.5F;
				// 落点取整：配合 0.5 缩放，避免半像素采样再丢一列
				float tx = Math.round(x);
				float ty = Math.round(y);
				g.pose().pushPose();
				g.pose().translate(tx, ty, 0.0F);
				g.pose().scale(scale, scale, 1.0F);
				// 一次文字 = 一串绘制调用：普通节点 1 次（与以前逐字相同），
				// 描边节点 9 次（8 次黑边 + 1 次本色字，本色字先抬 z 防 z-fighting）——
				// 次数/偏移/颜色/z 全部由 {@link OutlinedText#passes} 给出，这里只负责照单执行。
				for (OutlinedText.Pass pass : OutlinedText.passes(node, color)) {
					g.pose().pushPose();
					g.pose().translate(pass.dx(), pass.dy(), pass.z());
					g.drawString(mc.font, pass.text(), 0, 0, pass.color(), pass.shadow());
					g.pose().popPose();
				}
				g.pose().popPose();
			}
			// 按键条件 tooltip 在这里求值：shift 按下时整个换成展开版（真机 GuiStatBar.java:221-229）
			String resolvedTooltip = tooltipText(node);
			if (hovered && resolvedTooltip != null) {
				hoveredTooltip = resolvedTooltip;
			}
		}
		if (hoveredTooltip != null) {
			g.renderTooltip(mc.font, tooltipComponent(hoveredTooltip), mouseX, mouseY);
		}
	}

	/** 提示文本：写得像翻译键（含 {@code :}）就按翻译键处理，否则当字面量。 */
	private static Component tooltipComponent(String tooltip) {
		return tooltip.indexOf(':') > 0 ? Component.translatable(tooltip) : Component.literal(tooltip);
	}

	/**
	 * **节点列表认领了哪些存储槽**（节点绑定到槽位 = 它的外观由节点负责）。
	 *
	 * <p>框架据此决定"要不要自己补槽框"：没被认领的活跃槽位必须由框架画出来，
	 * 否则就会出现"鼠标摸得到、屏幕上没有"的**幽灵槽位** —— 交互却不可见，
	 * 正是"存在 ≡ 可见 ≡ 可命中"要禁止的状态。</p>
	 */
	public static Set<Integer> claimedSlots(ResourceLocation guiId, ChasmMenu menu) {
		List<GuiNode> nodes = nodes(guiId, menu);
		if (nodes.isEmpty()) {
			return Set.of();
		}
		Set<Integer> claimed = new HashSet<>();
		for (GuiNode node : nodes) {
			if (node.slotIndex() >= 0) {
				claimed.add(node.slotIndex());
			}
		}
		return claimed;
	}

	/** 当前帧节点数（调试用）。 */
	public static int nodeCount(ResourceLocation guiId, ChasmMenu menu) {
		return nodes(guiId, menu).size();
	}

	/** 供调试：列出当前节点（面板像素 + 层 + 动作）。 */
	public static List<String> describe(ResourceLocation guiId, ChasmMenu menu) {
		List<String> out = new ArrayList<>();
		for (GuiNode node : nodes(guiId, menu)) {
			GuiRect rect = node.rect(menu);
			out.add(node.key() + " " + rect + " " + node.layer()
				+ (node.action() == null ? "" : " -> " + node.action() + "#" + node.value()));
		}
		return out;
	}
}
