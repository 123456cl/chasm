package api.chasm.gui.client;

import api.chasm.gui.ChasmGui;
import api.chasm.gui.ChasmGuiRegistry;
import api.chasm.gui.ChasmGuiStateS2CPayload;
import api.chasm.gui.ChasmMenu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chasm UI 的客户端初始化：菜单屏幕绑定 + 数据同步接收 + 自定义绘制钩子注册。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>把每个已注册界面的 {@link net.minecraft.world.inventory.MenuType} 绑定到 {@link ChasmScreen}；
 *       （运行时机在所有模组 {@code main} 入口点之后，因此此时 {@link ChasmGuiRegistry} 已装配完）</li>
 *   <li>接收服务端 {@link ChasmGuiStateS2CPayload}（大值批量快照），按菜单 id 写入对应的
 *       {@link ChasmMenu}；</li>
 *   <li>每客户端 tick 推进一次数值平滑（{@link ChasmMenu#tickSmoothClient(double)}），
 *       让 20Hz 的服务端数值在 60fps 下平滑显示；</li>
 *   <li>提供 {@link #overlay(ResourceLocation, ChasmGuiOverlay)} 自定义绘制钩子。</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class ChasmGuiClient implements ClientModInitializer {

	/** 是否绘制键盘专注框（**默认 false**：默认亮黄描边会糊住控件内容，用户实测反馈）。 */
	private static volatile boolean focusRings;

	public static boolean showFocusRings() {
		return focusRings;
	}

	/** 模组按需打开专注框（默认关闭）。 */
	public static void setFocusRings(boolean value) {
		focusRings = value;
	}

	/** 平滑收敛系数（每客户端 tick；0.35 ≈ 5 tick 内追上目标）。 */
	private static final double SMOOTH_FACTOR = 0.35;

	/** 界面 id → 自定义绘制钩子（一个界面可挂多个，按注册顺序绘制）。 */
	private static final Map<ResourceLocation, List<ChasmGuiOverlay>> OVERLAYS = new ConcurrentHashMap<>();

	/** 注册一个界面的自定义绘制钩子（客户端调用；幂等追加）。 */
	public static void overlay(ResourceLocation guiId, ChasmGuiOverlay overlay) {
		OVERLAYS.computeIfAbsent(guiId, k -> new CopyOnWriteArrayList<>()).add(overlay);
	}

	/** 某界面的绘制钩子（无则空列表）。 */
	public static List<ChasmGuiOverlay> overlaysFor(ResourceLocation guiId) {
		List<ChasmGuiOverlay> list = OVERLAYS.get(guiId);
		return list == null ? List.of() : list;
	}

	@Override
	public void onInitializeClient() {
		for (ChasmGui gui : ChasmGuiRegistry.all()) {
			MenuScreens.register(gui.menuType(), ChasmScreen::from);
		}
		// 内置控件画法（滑块/进度条/开关）；第三方可继续 register 自己的种类
		ChasmWidgetRenderers.registerBuiltins();

		// 大值批量快照接收：按菜单 id 找到当前打开的界面并写入（回主线程，保证渲染线程读到一致值）
		ClientPlayNetworking.registerGlobalReceiver(ChasmGuiStateS2CPayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				if (context.client().player != null
					&& context.client().player.containerMenu instanceof ChasmMenu menu
					&& menu.containerId == payload.containerId()) {
					menu.receiveState(payload.values());
				}
			}));

		// 界面绑定信息（服务端 openAt 后下发；客户端仅用于显示/绘制）
		ClientPlayNetworking.registerGlobalReceiver(api.chasm.gui.ChasmGuiBindS2CPayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				if (context.client().player != null
					&& context.client().player.containerMenu instanceof ChasmMenu menu
					&& menu.containerId == payload.containerId()) {
					menu.acceptBinding(payload.pos(), net.minecraft.resources.ResourceKey.create(
						net.minecraft.core.registries.Registries.DIMENSION, payload.dimension()));
				}
			}));

		// 每客户端 tick 推进数值平滑（纯表现层）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && client.player.containerMenu instanceof ChasmMenu menu) {
				menu.tickSmoothClient(SMOOTH_FACTOR);
			}
		});
	}
}