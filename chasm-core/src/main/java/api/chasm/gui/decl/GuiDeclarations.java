package api.chasm.gui.decl;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * **声明式界面的注册表**（按界面 id 存"内容源 + 动作表"）。
 *
 * <p>为什么单独放一张表而不是塞进 {@code ChasmGui}：这样声明式层可以**完全旁挂**在现有
 * 界面上（不需要改动既有数据模型），旧界面照旧跑，新界面用新范式。</p>
 */
public final class GuiDeclarations {

	/** 节点点击动作：{@code (玩家, value)}。玩家参数用 Object 以免把 common 包绑死在服务端类型上。 */
	@FunctionalInterface
	public interface ActionHandler {
		void handle(Object player, int value, GuiState state);
	}

	/**
	 * **带坐标的动作处理器**：在 {@link ActionHandler} 之上多拿一个"点击点"——
	 * **面板像素空间**的 x/y（面板左上角为原点，与 {@link GuiNode#rect(api.chasm.gui.ChasmMenu)}
	 * 是同一个空间）。
	 *
	 * <p>真机出处：滑块的段位就是按鼠标 x 算的 ——
	 * {@code GuiSliderSegmented.calculateSegment:81-83} 的
	 * {@code Math.round((valueSteps - 1) * clamp((mouseX - refX - x) / width, 0, 1))}，
	 * 由 {@code :120-131} 的 {@code draw(...)} 在按住拖动时每帧调用。声明式节点拿不到鼠标，
	 * 这条通道就是给它的坐标来源。</p>
	 *
	 * <p><b>它同时就是 {@link ActionHandler}</b>：{@link #handle} 默认把坐标当缺失（NaN）转发。
	 * 因此注册后普通动作通道（{@link #actionOf}、探针诊断、无坐标时的回退）全都照常工作 ——
	 * 现有动作通道**零改动**。</p>
	 */
	@FunctionalInterface
	public interface PositionalHandler extends ActionHandler {

		/**
		 * @param player 触发的玩家
		 * @param x      点击点 x（面板像素；坐标不可用时为 {@link Double#NaN}）
		 * @param y      点击点 y（面板像素；坐标不可用时为 {@link Double#NaN}）
		 * @param value  节点的 {@link GuiNode#value()}
		 * @param state  当前界面状态
		 */
		void handleAt(Object player, double x, double y, int value, GuiState state);

		@Override
		default void handle(Object player, int value, GuiState state) {
			handleAt(player, Double.NaN, Double.NaN, value, state);
		}
	}

	/**
	 * **最近一次点击坐标**（面板像素空间）。
	 *
	 * @param x       面板像素 x
	 * @param y       面板像素 y
	 * @param present 是否真的有坐标（无客户端 / 从未点击 → false）
	 */
	public record ClickPoint(double x, double y, boolean present) {

		/** 哨兵：没有坐标。 */
		public static final ClickPoint NOWHERE = new ClickPoint(Double.NaN, Double.NaN, false);
	}

	private static final Map<ResourceLocation, GuiSource> SOURCES = new ConcurrentHashMap<>();
	private static final Map<ResourceLocation, Map<String, ActionHandler>> ACTIONS = new ConcurrentHashMap<>();
	/** 带坐标的动作表（与 {@link #ACTIONS} 并存：同一个 handler 对象同时进两张表）。 */
	private static final Map<ResourceLocation, Map<String, PositionalHandler>> POSITIONAL_ACTIONS =
		new ConcurrentHashMap<>();
	/**
	 * 每个界面最近一次的点击坐标，由**客户端**命中时写入（{@code DeclClient.hitTest}）。
	 *
	 * <p>服务端派发"带坐标的动作"时读它。单机 / 局域网主机（客户端与集成服务端同进程）拿到的
	 * 就是玩家点击的真实面板坐标；**专用服务器**上本表为空 → 处理器收到 NaN（如实降级，
	 * 见 {@link #dispatch}）。</p>
	 */
	private static final Map<ResourceLocation, ClickPoint> LAST_CLICK = new ConcurrentHashMap<>();

	private GuiDeclarations() {
	}

	public static void register(ResourceLocation guiId, GuiSource source, Map<String, ActionHandler> actions) {
		SOURCES.put(guiId, source);
		ACTIONS.put(guiId, Map.copyOf(actions));
		// 重新注册 = 重新声明：清掉上一轮的旁挂动作与坐标，避免旧 handler 劫持新界面
		POSITIONAL_ACTIONS.remove(guiId);
		LAST_CLICK.remove(guiId);
		ChasmLogger.info(guiId.getNamespace(), "注册声明式界面 {}（节点由状态派生，动作 {} 个）",
			guiId, actions.size());
	}

	public static GuiSource sourceOf(ResourceLocation guiId) {
		return guiId == null ? null : SOURCES.get(guiId);
	}

	/**
	 * **旁挂一个节点动作**到已注册的声明式界面：**保留**该界面已有的动作表，只新增/覆盖这一个 id。
	 *
	 * <p>用途是"界面声明处不在本模组手里"的场景 —— 端口要往一个既有界面补一行可点动作，
	 * 却不能改声明它的那个文件。{@link #register} 是整体替换（会丢掉别人的动作），这里是增量。</p>
	 *
	 * @return true = 界面已注册、动作已挂上；false = 该界面还没注册（什么都没做）
	 */
	public static boolean attachAction(ResourceLocation guiId, String actionId, ActionHandler handler) {
		if (guiId == null || actionId == null || handler == null) {
			return false;
		}
		Map<String, ActionHandler> existing = ACTIONS.get(guiId);
		if (existing == null) {
			return false;
		}
		Map<String, ActionHandler> merged = new HashMap<>(existing);
		merged.put(actionId, handler);
		ACTIONS.put(guiId, Map.copyOf(merged));
		ChasmLogger.info(guiId.getNamespace(), "界面 {} 旁挂节点动作 {}", guiId, actionId);
		return true;
	}

	/** 已注册的节点动作（{@link #attachAction} / {@link #attachPositionalAction} 之后的诊断入口；没有 → null）。 */
	public static ActionHandler actionOf(ResourceLocation guiId, String actionId) {
		if (guiId == null || actionId == null) {
			return null;
		}
		return ACTIONS.getOrDefault(guiId, Map.of()).get(actionId);
	}

	/**
	 * **旁挂一个"带坐标的动作"**到已注册的声明式界面 —— {@link #attachAction} 的带坐标版本。
	 *
	 * <p>节点照旧只写 {@code action(id, value)}；当这个 id 注册了 PositionalHandler 时，
	 * {@link #dispatch} 会把最近一次点击的**面板像素** x/y 一起交给它（真机
	 * {@code GuiSliderSegmented.calculateSegment:81-83} 的口径）。没有注册的 id 走原来的通道，
	 * **行为逐字不变**。</p>
	 *
	 * <p>同一个 handler 对象也会进普通动作表，所以 {@link #actionOf} / 探针的诊断字段
	 * （{@code handlerRegistered}）照常为真。</p>
	 *
	 * @return true = 界面已注册、动作已挂上；false = 该界面还没注册（什么都没做）
	 */
	public static boolean attachPositionalAction(ResourceLocation guiId, String actionId, PositionalHandler handler) {
		if (guiId == null || actionId == null || handler == null) {
			return false;
		}
		Map<String, ActionHandler> existing = ACTIONS.get(guiId);
		if (existing == null) {
			return false;
		}
		Map<String, PositionalHandler> merged = new HashMap<>(POSITIONAL_ACTIONS.getOrDefault(guiId, Map.of()));
		merged.put(actionId, handler);
		POSITIONAL_ACTIONS.put(guiId, Map.copyOf(merged));

		Map<String, ActionHandler> actions = new HashMap<>(existing);
		actions.put(actionId, handler);
		ACTIONS.put(guiId, Map.copyOf(actions));

		ChasmLogger.info(guiId.getNamespace(), "界面 {} 旁挂带坐标的节点动作 {}", guiId, actionId);
		return true;
	}

	/** 该动作是否注册了带坐标的版本（没有 → null）。 */
	public static PositionalHandler positionalActionOf(ResourceLocation guiId, String actionId) {
		if (guiId == null || actionId == null) {
			return null;
		}
		return POSITIONAL_ACTIONS.getOrDefault(guiId, Map.of()).get(actionId);
	}

	/**
	 * **记录一次点击坐标**（面板像素空间），由客户端命中时调用（{@code DeclClient.hitTest}）。
	 *
	 * <p>把"坐标"当成一份旁路状态，而不是塞进节点或动作表：节点仍然是纯函数产物，
	 * 服务端仍然按"第 index 个节点"派发；只有真正需要坐标的动作才去读它。</p>
	 */
	public static void recordClick(ResourceLocation guiId, double panelX, double panelY) {
		if (guiId == null) {
			return;
		}
		LAST_CLICK.put(guiId, new ClickPoint(panelX, panelY, true));
	}

	/** 该界面最近一次点击坐标（从没点过 / 专用服务器 → {@link ClickPoint#NOWHERE}）。 */
	public static ClickPoint lastClick(ResourceLocation guiId) {
		if (guiId == null) {
			return ClickPoint.NOWHERE;
		}
		ClickPoint point = LAST_CLICK.get(guiId);
		return point == null ? ClickPoint.NOWHERE : point;
	}

	/** 清掉某界面的点击坐标（界面关闭 / 测试隔离用）。 */
	public static void clearClick(ResourceLocation guiId) {
		if (guiId != null) {
			LAST_CLICK.remove(guiId);
		}
	}

	/**
	 * **包裹已注册界面的内容源**：新源 = {@code decorator(旧源)}。
	 *
	 * <p>同样给"不改界面声明处、只往节点列表末尾追加内容"的场景用。因为包裹的是**当前**源，
	 * 所以界面声明以后换了源也不会被这里覆盖掉 —— 比"用新源整个替换"更不容易过期。</p>
	 *
	 * @return true = 界面已注册、源已替换；false = 还没注册（什么都没做）
	 */
	public static boolean decorateSource(ResourceLocation guiId, UnaryOperator<GuiSource> decorator) {
		if (guiId == null || decorator == null) {
			return false;
		}
		GuiSource existing = SOURCES.get(guiId);
		if (existing == null) {
			return false;
		}
		GuiSource decorated = decorator.apply(existing);
		if (decorated == null) {
			return false;
		}
		SOURCES.put(guiId, decorated);
		return true;
	}

	public static boolean isDeclarative(ResourceLocation guiId) {
		return SOURCES.containsKey(guiId);
	}

	/**
	 * 服务端按"第 index 个节点"派发动作（客户端只发序号，不传坐标）。
	 *
	 * <p>带坐标的动作从这里取"最近一次点击坐标"（客户端命中时记的，见 {@link #recordClick}）。
	 * 只注册过普通动作的界面，走的还是原来那一句 {@code handler.handle(...)}，行为**零改动**。</p>
	 */
	public static void dispatch(ResourceLocation guiId, Object player, int index, GuiState state) {
		ClickPoint click = lastClick(guiId);
		dispatch(guiId, player, index, state, click.x(), click.y(), click.present());
	}

	/**
	 * 派发的完整版：**显式给出点击坐标**（面板像素）。离线测试与"坐标已知"的调用点走这条。
	 *
	 * @param hasPosition false = 坐标不可用（专用服务器 / 不是点击触发的派发）→ 带坐标的动作收到 NaN
	 * @return 是否真的派发到了处理器
	 */
	public static boolean dispatch(ResourceLocation guiId, Object player, int index, GuiState state,
		double x, double y, boolean hasPosition) {
		GuiSource source = SOURCES.get(guiId);
		if (source == null) {
			return false;
		}
		List<GuiNode> nodes = source.build(state);
		if (index < 0 || index >= nodes.size()) {
			ChasmLogger.warn(guiId.getNamespace(), "节点 {} 越界（本次共 {} 个）", index, nodes.size());
			return false;
		}
		GuiNode node = nodes.get(index);
		if (node.action() == null) {
			return false;
		}
		// 带坐标的通道优先：只有**显式注册过** PositionalHandler 的动作才走它
		PositionalHandler positional = positionalActionOf(guiId, node.action());
		if (positional != null) {
			positional.handleAt(player, hasPosition ? x : Double.NaN, hasPosition ? y : Double.NaN,
				node.value(), state);
			return true;
		}
		ActionHandler handler = ACTIONS.getOrDefault(guiId, Map.of()).get(node.action());
		if (handler == null) {
			ChasmLogger.warn(guiId.getNamespace(), "动作 {} 未注册", node.action());
			return false;
		}
		handler.handle(player, node.value(), state);
		return true;
	}
}
