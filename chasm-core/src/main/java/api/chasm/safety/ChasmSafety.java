package api.chasm.safety;

import api.chasm.log.ChasmLogger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Chasm 安全/容错助手 —— 面向"复杂、易报错崩溃、没容错"三大问题的高层封装。
 *
 * <p>目的：把反复手写的「判 null / 判服务端 / try-catch / 降级默认值」收敛成一行，
 * 让玩法代码更短、更不易崩、异常也只记日志不拖垮服务器。</p>
 *
 * 用法：
 * <pre>
 * // 仅服务端执行（null/客户端自动跳过），避免 NPE 与跨侧误操作
 * ChasmSafety.serverOnly(level, () -> doThing());
 *
 * // 一定给个安全默认值，出错降级而不抛
 * int t = ChasmSafety.getOr(be::getCurrentTick, 0);
 *
 * // 记日志地吞掉异常（不传播）
 * ChasmSafety.safe("mymod:pot_tick", () -> potTick());
 *
 * // 若在服务端玩家上下文做事
 * ChasmSafety.onServerPlayer(player, sp -> sp.getAdvancements()...);
 * </pre>
 */
public final class ChasmSafety {

	private ChasmSafety() {
	}

	/** world 是否非空且为服务端（null 安全）。 */
	public static boolean isServer(LevelAccessor level) {
		return level != null && !level.isClientSide();
	}

	/**
	 * 仅当 world 非空且为服务端时执行 action（否则静默跳过）。
	 * 玩法数值结算都应包进这里，避免客户端误操作与 null 世界崩溃。
	 */
	public static void serverOnly(LevelAccessor level, Runnable action) {
		if (isServer(level) && action != null) {
			try {
				action.run();
			} catch (Throwable t) {
				ChasmLogger.error("chasm", "ChasmSafety.serverOnly 执行异常（已隔离）: {}", String.valueOf(t));
			}
		}
	}

	/** 在服务端玩家上下文安全做事（null/非服务端跳过）。 */
	public static void onServerPlayer(net.minecraft.world.entity.player.Player player, Consumer<ServerPlayer> action) {
		if (player instanceof ServerPlayer sp && action != null) {
			try {
				action.accept(sp);
			} catch (Throwable t) {
				ChasmLogger.error("chasm", "ChasmSafety.onServerPlayer 异常（已隔离）: {}", String.valueOf(t));
			}
		}
	}

	/** 取安全默认值：supplier 出错/返回 null 时用 fallback（降级不抛）。 */
	public static <T> T getOr(Supplier<T> supplier, T fallback) {
		try {
			T v = supplier.get();
			return v != null ? v : fallback;
		} catch (Throwable t) {
			return fallback;
		}
	}

	/** 吞异常并记日志（不传播给上层，防止崩溃）。 */
	public static void safe(String context, Runnable action) {
		try {
			if (action != null) {
				action.run();
			}
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "{} 发生异常（已容错）: {}", context, String.valueOf(t));
		}
	}

	/** 非空校验的友好版：null 时给出默认并记 warn，避免随手 NPE。 */
	public static <T> T nonNullOr(T value, T fallback, String what) {
		if (value != null) {
			return value;
		}
		ChasmLogger.warn("chasm", "{} 为 null，已回退默认（防 NPE）", what);
		return fallback;
	}
}
