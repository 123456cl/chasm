package api.chasm.gui;

import api.chasm.net.RateLimiter;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 声明式界面的网络通道：注册按钮点击 C2S 包类型 + 服务端接收器。
 *
 * <p>在 {@link api.chasm.ChasmInit}（core 的主入口点）中 {@code init()}——服务端与客户端
 * 均会运行主入口点，因此包编解码在两侧都登记。按钮回调收到后在服务端主线程执行
 * （{@code player.getServer().execute(...)}），保证与服务端实体的访问安全。</p>
 *
 * <p><b>点击防刷</b>（两级冷却）：①<b>每按钮冷却</b>——对"每个玩家 × 每个界面 × 每个按钮"
 * 做冷却，下限 {@value #DEFAULT_COOLDOWN_MILLIS}ms（约 5 tick），若按钮声明了
 * {@code cooldownTicks} 且换算毫秒后更大则取较大者；②<b>每玩家全局冷却</b>——
 * {@value #GLOBAL_COOLDOWN_MILLIS}ms（1 tick）下限，堵住"轮换不同按钮/不同界面"绕过
 * 每按钮冷却的通道。任一冷却命中即被静默忽略。</p>
 */
public final class ChasmGuiChannel {

	/** 每按钮点击冷却下限（毫秒，约 5 tick）。 */
	private static final long DEFAULT_COOLDOWN_MILLIS = 250L;

	/** 每玩家全局点击冷却下限（毫秒，1 tick）：轮换按钮/界面的最终防线。 */
	private static final long GLOBAL_COOLDOWN_MILLIS = 50L;

	/** 每控件冷却（毫秒）：滑块拖动需要更高频率（20/s）。 */
	private static final long WIDGET_COOLDOWN_MILLIS = 50L;

	/** 控件总冷却（毫秒）：单玩家所有控件合计上限（50/s）。 */
	private static final long WIDGET_GLOBAL_COOLDOWN_MILLIS = 20L;

	/** 冷却表条目上限：超过才触发清理，避免无界增长。 */
	private static final int MAX_ENTRIES = 1024;

	/** 冷却表条目的最长存活时间（毫秒，5 分钟）：超过即视为可回收。 */
	private static final long ENTRY_TTL_MILLIS = 5 * 60_000L;

	/** 每按钮冷却表（作用域 = 界面 id + 按钮索引）。 */
	private static final RateLimiter PER_BUTTON_LIMITER =
		new RateLimiter(DEFAULT_COOLDOWN_MILLIS, MAX_ENTRIES, ENTRY_TTL_MILLIS);

	/** 每玩家全局冷却表（作用域固定 "global"）：所有按钮/界面共享同一把闸。 */
	private static final RateLimiter GLOBAL_LIMITER =
		new RateLimiter(GLOBAL_COOLDOWN_MILLIS, MAX_ENTRIES, ENTRY_TTL_MILLIS);

	/** 每控件冷却表。 */
	private static final RateLimiter WIDGET_LIMITER =
		new RateLimiter(WIDGET_COOLDOWN_MILLIS, MAX_ENTRIES, ENTRY_TTL_MILLIS);

	/** 控件总冷却表（独立于按钮闸门，避免滑块把按钮闸门占满）。 */
	private static final RateLimiter WIDGET_GLOBAL_LIMITER =
		new RateLimiter(WIDGET_GLOBAL_COOLDOWN_MILLIS, MAX_ENTRIES, ENTRY_TTL_MILLIS);

	private ChasmGuiChannel() {
	}

	/** 注册按钮点击通道（幂等；应在初始化早期调用一次）。 */
	public static void init() {
		PayloadTypeRegistry.playC2S().register(
			ChasmButtonClickC2SPayload.TYPE, ChasmButtonClickC2SPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(
			ChasmButtonClickC2SPayload.TYPE, ChasmGuiChannel::onButtonClick);
		PayloadTypeRegistry.playS2C().register(
			ChasmGuiBindS2CPayload.TYPE, ChasmGuiBindS2CPayload.CODEC);
		// 控件动作（滑块/开关/自定义控件）
		PayloadTypeRegistry.playC2S().register(
			ChasmWidgetActionC2SPayload.TYPE, ChasmWidgetActionC2SPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(
			ChasmWidgetActionC2SPayload.TYPE, ChasmGuiChannel::onWidgetAction);
	}

	private static void onWidgetAction(ChasmWidgetActionC2SPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		player.getServer().execute(() -> handleWidgetAction(player, payload));
	}

	/**
	 * 控件动作校验链（**绝不信任客户端**）：
	 * 界面存在 → 玩家确实开着该界面 → 控件索引有效 → **控件种类一致**（防篡改）→ 种类可交互 →
	 * 两级限流（每控件 50ms + 每玩家总 20ms）→ 值**夹取到声明值域** → 交给处理器（或自动回写数据键）。
	 */
	private static void handleWidgetAction(ServerPlayer player, ChasmWidgetActionC2SPayload payload) {
		ChasmGui gui = ChasmGuiRegistry.get(payload.guiId());
		if (gui == null) {
			return;
		}
		if (!(player.containerMenu instanceof ChasmMenu menu) || !menu.gui().id().equals(payload.guiId())) {
			return;
		}
		ChasmWidgetSpec spec = gui.widgetByIndex(payload.index());
		if (spec == null || !spec.kind().equals(payload.kind())) {
			return;
		}
		ChasmWidgetKind kind = ChasmWidgetKinds.get(spec.kind());
		if (kind == null || !kind.interactive()) {
			return;
		}
		if (!allowWidgetAction(player, spec)) {
			return;
		}
		int value = spec.clamp(payload.value());
		try {
			ChasmWidgetHandler handler = gui.widgetHandler(spec.key());
			if (handler != null) {
				handler.onAction(player, value, menu);
			} else if (menu.hasData(spec.key())) {
				// 未自定义处理器：默认把值写回绑定的整数数据键（客户端自动收到同步）
				menu.setData(spec.key(), value);
			}
		} catch (RuntimeException e) {
			api.chasm.log.ChasmLogger.error(gui.id().getNamespace(),
				"控件 {} 处理器异常: {}", spec.key(), e.toString());
		}
	}

	/** 控件限流：每控件 50ms（滑块拖动需要 20/s），每玩家总 20ms（封顶 50 次/秒）。 */
	private static boolean allowWidgetAction(ServerPlayer player, ChasmWidgetSpec spec) {
		UUID uid = player.getUUID();
		if (!WIDGET_GLOBAL_LIMITER.allow(uid, "widget-global", WIDGET_GLOBAL_COOLDOWN_MILLIS)) {
			return false;
		}
		return WIDGET_LIMITER.allow(uid, "widget:" + spec.key(), WIDGET_COOLDOWN_MILLIS);
	}

	/**
	 * 把"界面绑定到哪个方块/维度"告诉客户端（服务端调用，紧跟 {@code openMenu}）。
	 *
	 * <p>客户端只拿它做显示；**权限判定（{@link ChasmMenu#stillValid}）始终以服务端持有的绑定为准**。</p>
	 */
	static void sendBinding(ServerPlayer player, int containerId, net.minecraft.core.BlockPos pos,
							net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
		try {
			ServerPlayNetworking.send(player, new ChasmGuiBindS2CPayload(
				containerId, pos, dimension.location()));
		} catch (RuntimeException e) {
			api.chasm.log.ChasmLogger.error("chasm", "界面绑定信息发送失败: {}", e.toString());
		}
	}

	private static void onButtonClick(ChasmButtonClickC2SPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		// 服务端主线程安全执行（Fabric 分发线非主线程时也能正确落到服务端 tick 线程）
		player.getServer().execute(() -> handleClick(player, payload));
	}

	private static void handleClick(ServerPlayer player, ChasmButtonClickC2SPayload payload) {
		ChasmGui gui = ChasmGuiRegistry.get(payload.guiId());
		if (gui == null) {
			// 未知界面：可能是旧客户端发来的过期包，静默忽略
			return;
		}
		// 校验发起者确实打开着该界面，避免跨界面误触发
		if (!(player.containerMenu instanceof ChasmMenu menu) || !menu.gui().id().equals(payload.guiId())) {
			return;
		}
		ChasmButton button = gui.buttonByIndex(payload.index());
		if (button == null || !button.label().equals(payload.name())) {
			// 索引与按钮名不一致（索引错位/篡改），静默忽略
			return;
		}
		if (!allowClick(player, gui, payload, button)) {
			// 仍在冷却内，防高频刷服务器，静默忽略
			return;
		}
		try {
			button.handler().onClick(player, new ChasmGuiClick(gui.id(), button.label(), payload.index()));
		} catch (RuntimeException e) {
			api.chasm.log.ChasmLogger.error(gui.id().getNamespace(), "按钮 {} 回调异常: {}", button.label(), e.toString());
		}
	}

	/**
	 * 点击冷却判定（防刷）：每玩家全局冷却 + 每按钮冷却两级闸门。
	 *
	 * <p>每按钮冷却取「全局下限」与「该按钮声明 tick 数×50ms」的较大者；
	 * 全局冷却为固定 1 tick 下限，独立于按钮——即使轮换不同按钮/界面也无法突破。
	 * 首次点击与冷却过期的点击放行；冷却内的重复点击被忽略。</p>
	 */
	private static boolean allowClick(ServerPlayer player, ChasmGui gui,
		ChasmButtonClickC2SPayload payload, ChasmButton button) {
		UUID uid = player.getUUID();
		// 1) 每玩家全局冷却（独立于按钮/界面，堵轮换绕过）
		if (!GLOBAL_LIMITER.allow(uid, "global")) {
			return false;
		}
		// 2) 每按钮冷却（作用域 = 界面 id + 按钮索引）
		long perButtonMillis = button.cooldownTicks() * 50L;
		String scope = payload.guiId() + ":" + payload.index();
		return PER_BUTTON_LIMITER.allow(uid, scope, perButtonMillis);
	}
}