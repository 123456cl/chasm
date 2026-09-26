package api.chasm.spi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方法点注册表门面（SPI 门面）：按全局 id 登记/取用 {@link MethodPoint}，供跨模组共享与调度。
 */
public final class Spis {

	private static final Map<String, MethodPoint<?, ?>> POINTS = new ConcurrentHashMap<>();

	private Spis() {
	}

	/** 注册（幂等）并返回一个方法点；重复注册返回已存在的实例（不做覆盖，避免模组互相踩）。 */
	@SuppressWarnings("unchecked")
	public static <C, R> MethodPoint<C, R> point(String id, MethodFn<C, R> defaultImpl) {
		return (MethodPoint<C, R>) POINTS.computeIfAbsent(id, k -> MethodPoint.of(defaultImpl));
	}

	/** 同 {@link #point}，但允许后续显式 {@code replaceGlobal}（仅供框架作者对特殊点放行）。 */
	@SuppressWarnings("unchecked")
	public static <C, R> MethodPoint<C, R> mutablePoint(String id, MethodFn<C, R> defaultImpl) {
		return (MethodPoint<C, R>) POINTS.computeIfAbsent(id, k -> MethodPoint.mutable(defaultImpl));
	}

	/** 按 id 取方法点；未注册返回 null。 */
	@SuppressWarnings("unchecked")
	public static <C, R> MethodPoint<C, R> get(String id) {
		return (MethodPoint<C, R>) POINTS.get(id);
	}

	/** 已注册方法点数。 */
	public static int size() {
		return POINTS.size();
	}
}
