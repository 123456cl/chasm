package api.chasm.gui.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **界面动画状态**（框架此前完全没有这一层）。
 *
 * <p>为什么需要它：静态界面 = 贴图 + 即时高亮；好看的界面（Tetra 那种）靠的是**随时间过渡**：
 * 列表平滑滚动、行滑入、悬停高亮渐显、面板淡入。渲染器本来就能拿到 {@code partialTick}，
 * 但没有任何地方能存"上一帧的值"，于是只能画死。这里补上这块状态。</p>
 *
 * <p>三个语义：</p>
 * <ul>
 *   <li>{@link #smooth}：**指数逼近**目标值（0.15 秒级别的顺滑），适合滚动/高亮/缩放；</li>
 *   <li>{@link #entry}：**入场进度** 0→1（第一次出现时开始计时），适合淡入/滑入；</li>
 *   <li>{@link #reset}/{@link #onOpen}：界面打开时清状态，让动画每次都能重播。</li>
 * </ul>
 *
 * <p>线程与生命周期：只在客户端渲染线程访问；按 key 存，key 建议带界面 id 前缀避免撞车。</p>
 */
public final class ChasmGuiAnimations {

	/** 指数逼近的"每秒收敛速度"（越大越快；10 ≈ 0.1 秒到位）。 */
	public static final double DEFAULT_SPEED = 12.0;

	private static final Map<String, State> STATES = new ConcurrentHashMap<>();

	private ChasmGuiAnimations() {
	}

	private static final class State {
		double current;
		boolean initialized;
		long firstSeenNanos;
		long lastNanos;
		/** 时间轴：触发时刻、触发时的当前值、上一帧算出的值（供 trigger 的 relativeStart 用）。 */
		boolean timelineStarted;
		long timelineStartNanos;
		double timelineStartValue;
		double timelineCurrent;
	}

	/**
	 * **触发一条时间轴**（可选重置为"从当前值开始"，与 mutil 的 {@code relativeStart} 一致）。
	 *
	 * <p>真实语义（mutil {@code KeyframeAnimation}）：一次性的多段线性动画，
	 * 触发时以**当前值**为起点；连发时因为起点不是 0，上升段会自然变短，峰值不变。
	 * 这正是原版 flash 连点时的表现。</p>
	 */
	public static void trigger(String key) {
		State s = state(key);
		// relativeStart：以**当前值**为起点（mutil 的 Applier 默认行为），所以连发时上升段会变短
		s.timelineStartValue = s.timelineCurrent;
		s.timelineStarted = true;
		s.timelineStartNanos = System.nanoTime();
	}

	/**
	 * **分段线性时间轴**（复刻 mutil 的 {@code KeyframeAnimation}：毫秒、纯线性、无缓动）。
	 *
	 * @param key          动画键
	 * @param rest         未触发时的静止值（例如闪烁覆盖层 = 0）
	 * @param delayMs      触发后先等多久（对应 {@code withDelay}）
	 * @param durationsMs  每段时长（毫秒）
	 * @param targets      每段终点值（从触发时的当前值线性走到 targets[0]，再到 targets[1]…）
	 */
	public static double timeline(String key, double rest, long delayMs, long[] durationsMs, double[] targets) {
		State s = state(key);
		if (!s.timelineStarted || durationsMs.length == 0 || durationsMs.length != targets.length) {
			s.timelineCurrent = rest;
			return rest;
		}
		long elapsedMs = (System.nanoTime() - s.timelineStartNanos) / 1_000_000L - delayMs;
		double value = s.timelineStartValue;
		if (elapsedMs > 0) {
			long acc = 0;
			for (int i = 0; i < durationsMs.length; i++) {
				long duration = Math.max(1L, durationsMs[i]);
				if (elapsedMs >= acc + duration) {
					value = targets[i];
					acc += duration;
					continue;
				}
				double t = (elapsedMs - acc) / (double) duration;   // 线性，无缓动（与 mutil 一致）
				value = value + (targets[i] - value) * t;
				break;
			}
		}
		s.timelineCurrent = value;
		return value;
	}

	/** 该时间轴是否已经跑完（可用来做一次性收尾）。 */
	public static boolean timelineFinished(String key, long delayMs, long[] durationsMs) {
		State s = STATES.get(key);
		if (!s.timelineStarted) {
			return false;
		}
		long total = delayMs;
		for (long d : durationsMs) {
			total += Math.max(1L, d);
		}
		return (System.nanoTime() - s.timelineStartNanos) / 1_000_000L >= total;
	}

	private static State state(String key) {
		return STATES.computeIfAbsent(key, k -> new State());
	}

	/**
	 * **平滑逼近**目标值（帧率无关的指数插值）。
	 *
	 * @param key   动画键（建议 {@code "界面id:控件键:行号"}）
	 * @param target 目标值
	 * @param speed  每秒收敛速度（越大越快，见 {@link #DEFAULT_SPEED}）
	 */
	public static double smooth(String key, double target, double speed) {
		State s = state(key);
		long now = System.nanoTime();
		if (!s.initialized) {
			s.current = target;
			s.initialized = true;
			s.lastNanos = now;
			s.firstSeenNanos = now;
			return target;
		}
		double dt = Math.min(0.25, (now - s.lastNanos) / 1.0E9);
		s.lastNanos = now;
		double factor = Math.min(1.0, dt * speed);
		s.current += (target - s.current) * factor;
		if (Math.abs(target - s.current) < 1.0E-3) {
			s.current = target;
		}
		return s.current;
	}

	/** 便捷：默认速度的平滑逼近。 */
	public static double smooth(String key, double target) {
		return smooth(key, target, DEFAULT_SPEED);
	}

	/**
	 * **入场进度**：该 key 第一次被画之后的 {@code durationSeconds} 内从 0 线性到 1。
	 * 适合面板淡入、行滑入（配合 {@link #smooth} 可做成回弹）。
	 */
	public static double entry(String key, double durationSeconds) {
		State s = state(key);
		long now = System.nanoTime();
		if (!s.initialized) {
			s.initialized = true;
			s.firstSeenNanos = now;
			s.lastNanos = now;
		}
		if (durationSeconds <= 0) {
			return 1.0;
		}
		double progress = (now - s.firstSeenNanos) / 1.0E9 / durationSeconds;
		return Math.max(0.0, Math.min(1.0, progress));
	}

	/** 便捷：默认 0.18 秒的入场进度。 */
	public static double entry(String key) {
		return entry(key, 0.18);
	}

	/** 平滑整数滚动（列表用；返回可直接当"第几行"的浮点，画的时候带上小数做顺滑滚动）。 */
	public static double smoothScroll(String key, int target, double speed) {
		return smooth(key + ":scroll", target, speed);
	}

	/** 该动画当前值（未初始化则返回 fallback）。 */
	public static double current(String key, double fallback) {
		State s = STATES.get(key);
		return s == null || !s.initialized ? fallback : s.current;
	}

	/** 重置单个动画（例如某行被移除了）。 */
	public static void reset(String key) {
		STATES.remove(key);
	}

	/** 清空全部动画状态（界面关闭时调用，保证下次打开动画重播）。 */
	public static void resetAll() {
		STATES.clear();
	}

	/** 界面打开时调用：以该界面 id 为前缀的动画全部重播。 */
	public static void onOpen(String guiId) {
		String prefix = guiId + ":";
		STATES.keySet().removeIf(key -> key.startsWith(prefix));
	}

	/** 当前记录的动画数量（调试用）。 */
	public static int size() {
		return STATES.size();
	}
}
