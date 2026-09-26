package api.chasm.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端网络限流器（防刷共享工具，供按键通道/界面按钮通道复用）。
 *
 * <p>对"玩家 × 作用域"做基于时间戳的冷却判定：首次调用与冷却过期的调用放行；
 * 冷却内的重复调用被静默忽略。作用域由调用方提供（如 {@code "gui:mymod:menu:3"}、
 * {@code "key:mymod:magic_blast"}），可精确到单个按钮/单个按键，也可用固定作用域
 * （如 {@code "gui-global"}）实现"每玩家全局冷却下限"，堵住轮换按钮/轮换按键的绕过。</p>
 *
 * <p>表有界：仅当条目数超过 {@code maxEntries} 才触发清理，回收存活超过
 * {@code entryTtlMillis} 的冷门键，防止长期服务器对冷门作用域累积内存。</p>
 */
public final class RateLimiter {

	/** 冷却 key：玩家 UUID + 作用域字符串。 */
	public record Key(UUID player, String scope) {
	}

	/** 全局冷却下限（毫秒）。 */
	private final long defaultCooldownMillis;
	/** 冷却表条目上限，超过才清理。 */
	private final int maxEntries;
	/** 冷门条目最长存活时间（毫秒）。 */
	private final long entryTtlMillis;

	/** 最近一次调用时间表（key → 时间戳）。 */
	private final Map<Key, Long> lastCalls = new ConcurrentHashMap<>();

	public RateLimiter(long defaultCooldownMillis, int maxEntries, long entryTtlMillis) {
		this.defaultCooldownMillis = defaultCooldownMillis;
		this.maxEntries = maxEntries;
		this.entryTtlMillis = entryTtlMillis;
	}

	/**
	 * 冷却判定：冷却取「全局下限」与「额外毫秒」的较大者。
	 *
	 * @return true = 放行；false = 仍在冷却内，应静默忽略
	 */
	public boolean allow(UUID player, String scope) {
		return allow(player, scope, 0L);
	}

	/**
	 * 冷却判定：冷却取「全局下限」与「额外毫秒」的较大者。
	 *
	 * @param extraMillis 额外冷却（如按钮声明的 tick 数换算毫秒），可传 0
	 */
	public boolean allow(UUID player, String scope, long extraMillis) {
		long cooldown = Math.max(defaultCooldownMillis, extraMillis);
		Key key = new Key(player, scope);
		long now = System.currentTimeMillis();
		sweepIfNeeded(now);
		Long last = lastCalls.putIfAbsent(key, now);
		if (last == null) {
			return true; // 首次调用：记录并放行
		}
		if (now - last < cooldown) {
			return false; // 冷却内：静默忽略（防刷）
		}
		lastCalls.computeIfPresent(key, (k, v) -> now); // 冷却过期：更新时间并放行
		return true;
	}

	/** 有界清理：仅当条目数超阈值时回收过期冷门键，未超阈值零开销。 */
	private void sweepIfNeeded(long now) {
		if (lastCalls.size() < maxEntries) {
			return;
		}
		lastCalls.entrySet().removeIf(e -> now - e.getValue() > entryTtlMillis);
	}
}
