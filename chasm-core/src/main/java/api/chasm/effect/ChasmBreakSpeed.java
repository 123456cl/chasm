package api.chasm.effect;

import api.chasm.log.ChasmLogger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * **破坏速度钩子**（框架能力）—— 让"玩家挖掘某个方块时的动态速度修正"成为可插拔的扩展点。
 *
 * <h2>为什么必须走属性，而不是覆写方法</h2>
 * <p>真实 Tetra 走 Forge 的 {@code PlayerEvent.BreakSpeed}（{@code ItemEffectHandler.java:433-439} 注册，
 * {@code ReachingEffect.java:12-27} 消费）。1.21.1 + Fabric API 0.116 **没有**等价的破坏速度事件
 * （只有 {@code PlayerBlockBreakEvents}，它管的是"破坏前/后"，改不了速度）。</p>
 *
 * <p>但原版 {@code Player#getDestroySpeed(BlockState)} 的字节码里有一条公开的加法通道（javap 反汇编确认）：</p>
 * <pre>
 * f = inventory.getDestroySpeed(state);          // = 手持物品的 Item#getDestroySpeed
 * if (f &gt; 1.0F) f += getAttributeValue(Attributes.MINING_EFFICIENCY);
 * ... 挖掘加速/缓慢/水下/离地 ...
 * </pre>
 * <p>所以"把倍率 m 变成追加量 {@code base x (m - 1)}"就能得到与 {@code event.setNewSpeed(speed x m)}
 * **数值完全等价**的结果（{@code base + base x (m-1) = base x m}），且零 mixin：只往玩家的
 * {@code MINING_EFFICIENCY} 上挂一个**临时**修饰符（不进存档，{@code addOrUpdateTransientModifier}）。</p>
 *
 * <h2>时机（服务端权威）</h2>
 * <ul>
 *   <li>玩家开始破坏方块：服务端 {@code AttackBlockCallback}（Fabric 的 {@code ServerPlayerInteractionManagerMixin:58-61}
 *       只在 {@code START_DESTROY_BLOCK} 上触发）→ {@link #onStartBreaking} 记录目标并算一次追加量；</li>
 *   <li>服务端每 tick 在 {@code ServerPlayerGameMode.tick()} 里用 {@code BlockState#getDestroyProgress}
 *       → {@code Player#getDestroySpeed} 取值，因此挂上即可生效；</li>
 *   <li>方块被破坏（{@code PlayerBlockBreakEvents.AFTER}）或超过 TTL（默认 200 tick = 10 秒，
 *       与真实 {@code CritEffect.java:28-31} 的 10 秒缓存同量级）→ {@link #onBroken} / {@link #tick} 撤销。</li>
 * </ul>
 *
 * <h2>复杂度</h2>
 * <p>注册/查询都是 {@code ConcurrentHashMap} 的 O(1)；每个玩家最多一条记录（{@code WeakHashMap}，
 * 玩家下线自然回收）；每次计算只遍历已注册的 provider（数量是个位数）。</p>
 */
public final class ChasmBreakSpeed {

	/** 破坏速度修正提供者：返回**倍率**（1 = 不改变）。 */
	@FunctionalInterface
	public interface Provider {
		/**
		 * @param player    正在挖掘的玩家
		 * @param stack     手持物品栈（可能为空栈）
		 * @param state     目标方块状态
		 * @param pos       目标方块坐标
		 * @param baseSpeed 物品自身的破坏速度（{@code Item#getDestroySpeed}）
		 * @return 倍率（≤ 0 或 NaN 一律按 1 处理）
		 */
		float multiplier(Player player, ItemStack stack, BlockState state, BlockPos pos, float baseSpeed);
	}

	/** 修饰符 id（**确定性**：同 id 覆盖，绝不叠加出幽灵修饰符）。 */
	public static final ResourceLocation MODIFIER_ID =
		ResourceLocation.fromNamespaceAndPath("chasm", "break_speed");

	/** 记录存活 tick 数（真实 {@code CritEffect} 的 10 秒缓存同量级）。 */
	public static final int DEFAULT_TTL_TICKS = 200;

	private static final Map<String, Provider> PROVIDERS = new java.util.concurrent.ConcurrentHashMap<>();
	/** 玩家 → 当前正在挖的目标（服务端主线程访问；WeakHashMap 避免玩家实体被长期引用）。 */
	private static final Map<Player, Tracked> TRACKED = Collections.synchronizedMap(new WeakHashMap<>());

	private static final int MAX_TRACKED = 256;

	/** 一条追踪记录：目标 + 方块状态快照 + 剩余 tick。 */
	private static final class Tracked {
		private final BlockPos pos;
		private final BlockState state;
		private final ItemStack stack;
		private int ttl;

		private Tracked(BlockPos pos, BlockState state, ItemStack stack, int ttl) {
			this.pos = pos;
			this.state = state;
			this.stack = stack;
			this.ttl = ttl;
		}
	}

	private ChasmBreakSpeed() {
	}

	// ------------------------------------------------------------------ 注册

	/** 注册一个提供者（按名字唯一，重复注册覆盖；名字为 null 直接忽略）。 */
	public static void registerProvider(String name, Provider provider) {
		if (name == null || provider == null) {
			return;
		}
		PROVIDERS.put(name, provider);
		ChasmLogger.call("chasm", "ChasmBreakSpeed", "registerProvider", "注册破坏速度提供者 {}", name);
	}

	/** 注销一个提供者。 */
	public static void unregisterProvider(String name) {
		if (name != null && PROVIDERS.remove(name) != null) {
			ChasmLogger.call("chasm", "ChasmBreakSpeed", "unregisterProvider", "注销破坏速度提供者 {}", name);
		}
	}

	/** 已注册的提供者数（调试/测试）。 */
	public static int providerCount() {
		return PROVIDERS.size();
	}

	/** 是否有人在监听（无人注册 → 事件源零开销早退）。 */
	public static boolean isActive() {
		return !PROVIDERS.isEmpty();
	}

	// ------------------------------------------------------------------ 纯函数（可单测）

	/**
	 * 连乘各提供者的倍率（**纯函数**）：{@code ≤ 0} 或 NaN 的返回值一律按 1 处理
	 * —— 一个写错的扩展点不该把玩家的挖掘速度变成 0 或 NaN。
	 */
	public static float combineMultipliers(float... multipliers) {
		float product = 1.0F;
		if (multipliers == null) {
			return product;
		}
		for (float value : multipliers) {
			if (Float.isFinite(value) && value > 0.0F) {
				product *= value;
			}
		}
		return Float.isFinite(product) && product > 0.0F ? product : 1.0F;
	}

	/**
	 * 倍率 → {@code MINING_EFFICIENCY} 追加量（**纯函数**）：{@code base x (m - 1)}。
	 *
	 * <p>原版只在 {@code base > 1} 时才加这条属性（javap 反汇编），所以 base ≤ 1 时追加量无意义 ——
	 * 本方法照实返回，由调用方按原版条件决定是否挂。</p>
	 */
	public static double addendFor(float baseSpeed, float multiplier) {
		if (!Float.isFinite(baseSpeed) || baseSpeed <= 0.0F) {
			return 0.0D;
		}
		if (!Float.isFinite(multiplier) || multiplier <= 0.0F) {
			return 0.0D;
		}
		return baseSpeed * (multiplier - 1.0F);
	}

	/** 应用倍率后的破坏速度（纯函数；仅供断言/调试，与"原版读属性"的结果一致）。 */
	public static float applyMultiplier(float baseSpeed, float multiplier) {
		if (!Float.isFinite(baseSpeed)) {
			return 0.0F;
		}
		float safe = Float.isFinite(multiplier) && multiplier > 0.0F ? multiplier : 1.0F;
		return baseSpeed * safe;
	}

	/** 把 {@link #combineMultipliers} 的结果作用到基础速度上（纯函数）。 */
	public static float applyProviders(float baseSpeed, float... multipliers) {
		return applyMultiplier(baseSpeed, combineMultipliers(multipliers));
	}

	// ------------------------------------------------------------------ 事件源

	/**
	 * 玩家开始破坏一个方块（服务端 {@code START_DESTROY_BLOCK}）。
	 *
	 * <p>立即算出追加量并挂上临时修饰符 —— 服务端下一 tick 的破坏进度就会用上它。</p>
	 */
	public static void onStartBreaking(Player player, BlockPos pos, BlockState state) {
		if (player == null || pos == null || state == null || player.level().isClientSide() || !isActive()) {
			return;
		}
		try {
			ItemStack stack = player.getMainHandItem();
			double addend = computeAddend(player, stack, state, pos);
			apply(player, addend);
			synchronized (TRACKED) {
				if (TRACKED.size() >= MAX_TRACKED) {
					// 有界：超限时整表清空并撤销（绝不无界增长）
					clearAllTracked();
				}
				TRACKED.put(player, new Tracked(pos.immutable(), state, stack, DEFAULT_TTL_TICKS));
			}
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "破坏速度修正失败（已隔离）", t);
		}
	}

	/** 方块真的被破坏了（服务端 {@code PlayerBlockBreakEvents.AFTER}）→ 撤销修正。 */
	public static void onBroken(Player player, BlockPos pos) {
		if (player == null) {
			return;
		}
		Tracked tracked;
		synchronized (TRACKED) {
			tracked = TRACKED.get(player);
			if (tracked == null || (pos != null && !tracked.pos.equals(pos))) {
				return;
			}
			TRACKED.remove(player);
		}
		removeModifier(player);
	}

	/** 服务端 tick：倒计时并撤销过期记录（目标方块变了也算过期）。 */
	public static void tick() {
		if (TRACKED.isEmpty()) {
			return;
		}
		List<Player> expired = null;
		synchronized (TRACKED) {
			var iterator = TRACKED.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<Player, Tracked> entry = iterator.next();
				Tracked tracked = entry.getValue();
				boolean stale = tracked == null || --tracked.ttl <= 0;
				if (!stale) {
					Player player = entry.getKey();
					stale = player == null || player.isRemoved()
						|| !player.level().getBlockState(tracked.pos).equals(tracked.state);
				}
				if (stale) {
					iterator.remove();
					if (expired == null) {
						expired = new ArrayList<>(2);
					}
					expired.add(entry.getKey());
				}
			}
		}
		if (expired != null) {
			for (Player player : expired) {
				removeModifier(player);
			}
		}
	}

	/** 撤销某玩家的破坏速度修正（下线/换手/取消时调用都安全）。 */
	public static void clear(Player player) {
		if (player == null) {
			return;
		}
		synchronized (TRACKED) {
			TRACKED.remove(player);
		}
		removeModifier(player);
	}

	/** 当前追踪的玩家数（调试/测试）。 */
	public static int trackedCount() {
		return TRACKED.size();
	}

	// ------------------------------------------------------------------ 内部

	/** 算一次追加量（provider 异常逐个隔离）。 */
	private static double computeAddend(Player player, ItemStack stack, BlockState state, BlockPos pos) {
		float base = 0.0F;
		try {
			base = stack == null || stack.isEmpty() ? 0.0F : stack.getItem().getDestroySpeed(stack, state);
		} catch (Throwable t) {
			ChasmLogger.warn("chasm", "读取物品破坏速度失败（按 0 处理）: {}", String.valueOf(t));
		}
		if (!(base > 1.0F)) {
			// 原版只在 base > 1 时才加 MINING_EFFICIENCY（javap 反汇编），提前退出省掉整轮 provider
			return 0.0D;
		}
		float product = 1.0F;
		for (Map.Entry<String, Provider> entry : PROVIDERS.entrySet()) {
			Provider provider = entry.getValue();
			if (provider == null) {
				continue;
			}
			try {
				float value = provider.multiplier(player, stack, state, pos, base);
				if (Float.isFinite(value) && value > 0.0F) {
					product *= value;
				}
			} catch (Throwable t) {
				ChasmLogger.error("chasm", "破坏速度提供者 {} 异常（已隔离）", entry.getKey(), t);
			}
		}
		return addendFor(base, product);
	}

	/** 挂/更新/撤销临时修饰符（O(1)，幂等）。 */
	private static void apply(Player player, double addend) {
		AttributeInstance instance = player.getAttribute(Attributes.MINING_EFFICIENCY);
		if (instance == null) {
			return;
		}
		AttributeModifier existing = instance.getModifier(MODIFIER_ID);
		if (addend == 0.0D || !Double.isFinite(addend)) {
			if (existing != null) {
				instance.removeModifier(MODIFIER_ID);
			}
			return;
		}
		if (existing != null && Math.abs(existing.amount() - addend) < 1.0E-6D) {
			return;
		}
		instance.addOrUpdateTransientModifier(
			new AttributeModifier(MODIFIER_ID, addend, AttributeModifier.Operation.ADD_VALUE));
	}

	private static void removeModifier(Player player) {
		try {
			AttributeInstance instance = player.getAttribute(Attributes.MINING_EFFICIENCY);
			if (instance != null && instance.getModifier(MODIFIER_ID) != null) {
				instance.removeModifier(MODIFIER_ID);
			}
		} catch (Throwable t) {
			ChasmLogger.warn("chasm", "撤销破坏速度修饰符失败: {}", String.valueOf(t));
		}
	}

	private static void clearAllTracked() {
		synchronized (TRACKED) {
			for (Player player : TRACKED.keySet()) {
				removeModifier(player);
			}
			TRACKED.clear();
		}
	}
}
