package api.chasm.key;

import api.chasm.item.ChasmItemSupport;
import api.chasm.item.context.KeyContext;
import api.chasm.log.ChasmLogger;
import api.chasm.net.RateLimiter;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

/**
 * 自定义按键的网络通道：注册按键 C2S 包类型 + 服务端接收器。
 *
 * <p>在 {@link api.chasm.ChasmInit}（core 主入口点）中 {@code init()}——服务端与客户端
 * 均会运行主入口点，因此包编解码在两侧都登记。回调在<b>服务端主线程</b>执行
 * （{@code player.getServer().execute(...)}），保证与服务端实体的访问安全。</p>
 *
 * <p><b>安全校验</b>：收到按键后必须校验玩家<b>确实在该手持有</b>所声称的物品，
 * 再按物品 id 反查其行为载体中绑定到该按键的处理器；任何一环不满足（未知物品/
 * 未持有/未绑定）都静默忽略，杜绝伪造。</p>
 *
 * <p><b>防刷限流</b>：服务端对"每个玩家 × 每个按键"做冷却（下限
 * {@value #DEFAULT_COOLDOWN_MILLIS}ms，约 5 tick），冷却内的重复按键被静默忽略，
 * 阻止恶意客户端高频刷按键包触发服务端回调。</p>
 */
public final class ChasmKeyChannel {

	/** 每玩家 × 每按键的触发冷却下限（毫秒，约 5 tick）。 */
	private static final long DEFAULT_COOLDOWN_MILLIS = 250L;

	/** 冷却表条目上限：超过才触发清理，避免无界增长。 */
	private static final int MAX_ENTRIES = 1024;

	/** 冷却表条目的最长存活时间（毫秒，5 分钟）：超过即视为可回收。 */
	private static final long ENTRY_TTL_MILLIS = 5 * 60_000L;

	/** 按键触发冷却表（作用域 = 按键 id）。 */
	private static final RateLimiter LIMITER =
		new RateLimiter(DEFAULT_COOLDOWN_MILLIS, MAX_ENTRIES, ENTRY_TTL_MILLIS);

	private ChasmKeyChannel() {
	}

	/** 注册按键通道（幂等；应在初始化早期调用一次）。 */
	public static void init() {
		PayloadTypeRegistry.playC2S().register(KeyPressC2SPayload.TYPE, KeyPressC2SPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(KeyPressC2SPayload.TYPE, ChasmKeyChannel::onKeyPress);
	}

	private static void onKeyPress(KeyPressC2SPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		player.getServer().execute(() -> handle(player, payload));
	}

	private static void handle(ServerPlayer player, KeyPressC2SPayload payload) {
		ResourceLocation itemId = payload.itemId();
		Item item = BuiltInRegistries.ITEM.get(itemId);
		// 校验 1：物品存在且非空气
		if (item == Items.AIR) {
			return;
		}
		InteractionHand hand = payload.hand();
		ItemStack stack = player.getItemInHand(hand);
		// 校验 2：玩家确实在该手持有该物品（防止伪造/不持有凭空触发）
		if (stack.isEmpty() || stack.getItem() != item) {
			return;
		}
		// 校验 3：物品是 Chasm 物品且绑定过该按键的处理器
		if (!(item instanceof ChasmItemSupport support)) {
			return;
		}
		Consumer<KeyContext> handler = support.chasmBehavior().keyHandler(payload.keyId());
		if (handler == null) {
			return;
		}
		// 校验 4：每玩家 × 每按键冷却（防高频刷服务器，静默忽略冷却内触发）
		if (!LIMITER.allow(player.getUUID(), payload.keyId().toString())) {
			return;
		}
		ChasmLogger.call(payload.keyId().getNamespace(), "KeyChannel", "key_press",
			"玩家 {} 以 {} 手持 {} 触发按键 {}",
			player.getGameProfile().getName(), hand, itemId, payload.keyId());
		try {
			handler.accept(new KeyContext(player.level(), player, stack, payload.keyId()));
		} catch (RuntimeException e) {
			ChasmLogger.error(payload.keyId().getNamespace(), "按键 {} 回调异常: {}",
				payload.keyId(), e.toString());
		}
	}
}