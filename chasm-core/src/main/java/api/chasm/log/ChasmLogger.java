package api.chasm.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chasm 统一日志系统（零侵入，纯对 SLF4J 的增强包装 + 会话文件落盘）。
 *
 * <p>设计目标：让每一行日志都能回答「哪个模组」—— 自动为每条日志加上
 * {@code [modid]} 前缀，并提供结构化的调用记录（CALL）与注入记录（INJECT），
 * 输出格式稳定、可预测，便于人工与 AI 分析。</p>
 *
 * <p>特点：</p>
 * <ul>
 *   <li>按 modId 隔离 Logger（{@link #get(String)}，懒加载）</li>
 *   <li>调试模式（{@link #setDebugMode(boolean)}），开启后输出 {@code [DEBUG]} 级与调用栈信息</li>
 *   <li>结构化方法：{@link #call(String, String, String, String, Object...)} 记录一次调用，
 *       {@link #injection(String, String, String, String)} 记录一次 Mixin 注入</li>
 *   <li><b>会话文件落盘</b>：每次日志方法在控制台输出后，同步拼接一行完整日志
 *       入队到 {@link ChasmFileLogger}（异步写入 {@code chasm-logs/<时间戳>/session.log}），
 *       不阻塞主线程；游戏退出时由 JVM 关闭钩子 flush</li>
 *   <li>所有文件 IO 均被隔离与吞异常，日志系统绝不影响游戏主流程</li>
 * </ul>
 */
public final class ChasmLogger {

	private static final Map<String, Logger> LOGGERS = new ConcurrentHashMap<>();
	private static volatile boolean debugMode = false;

	/** 会话文件日志器（懒加载，写文件失败则保持 null，日志系统退化为仅控制台）。 */
	private static volatile ChasmFileLogger fileLogger;
	/** 会话文件日志器初始化已失败标志：防止 catch 降级路径无限递归重试导致 StackOverflowError。 */
	private static volatile boolean fileInitFailed = false;

	private ChasmLogger() {
	}

	/** 开启调试模式（输出 {@code [DEBUG]} 级日志）。 */
	public static void enableDebug() {
		debugMode = true;
	}

	/** 关闭调试模式。 */
	public static void disableDebug() {
		debugMode = false;
	}

	/** 当前是否处于调试模式。 */
	public static boolean isDebugEnabled() {
		return debugMode;
	}

	/** 设置调试模式开关。 */
	public static void setDebugMode(boolean debug) {
		debugMode = debug;
	}

	/**
	 * 获取指定模组的 SLF4J Logger（按 modId 命名，懒加载，线程安全）。
	 *
	 * @param modId 模组命名空间
	 */
	public static Logger get(String modId) {
		return LOGGERS.computeIfAbsent(modId, id -> LoggerFactory.getLogger(id));
	}

	/** INFO 级日志（自动加 {@code [modid]} 前缀）。 */
	public static void info(String modId, String format, Object... args) {
		String msg = render(format, args);
		get(modId).info("[{}] {}", modId, msg);
		fileEnqueue(modId, "INFO", msg);
	}

	/** WARN 级日志（自动加 {@code [modid]} 前缀）。 */
	public static void warn(String modId, String format, Object... args) {
		String msg = render(format, args);
		get(modId).warn("[{}] {}", modId, msg);
		fileEnqueue(modId, "WARN", msg);
	}

	/** ERROR 级日志（自动加 {@code [modid]} 前缀）。 */
	public static void error(String modId, String format, Object... args) {
		String msg = render(format, args);
		get(modId).error("[{}] {}", modId, msg);
		fileEnqueue(modId, "ERROR", msg);
	}

	/** DEBUG 级日志，仅调试模式开启时输出（自动加 {@code [modid][DEBUG]} 前缀）。 */
	public static void debug(String modId, String format, Object... args) {
		if (!debugMode) {
			return;
		}
		String msg = render(format, args);
		get(modId).debug("[{}][DEBUG] {}", modId, msg);
		fileEnqueue(modId, "DEBUG", msg);
	}

	/**
	 * 记录一次「调用」：结构化输出 类名.方法名 + 详情。
	 *
	 * <pre>{@code
	 * ChasmLogger.call("mymod", "ChasmRegistration", "registerItem",
	 *     "注册物品 mymod:mana_sword");
	 * // → [INFO] [mymod] CALL ChasmRegistration.registerItem: 注册物品 mymod:mana_sword
	 * }</pre>
	 *
	 * @param modId      模组命名空间
	 * @param className  调用发生的类名（可含包路径）
	 * @param methodName 调用的方法名
	 * @param detail     详情描述，支持 SLF4J {@code {}} 占位符
	 * @param args       占位符实参
	 */
	public static void call(String modId, String className, String methodName, String detail, Object... args) {
		String msg = render("CALL {}.{}: " + detail, insertPrefixArgs(className, methodName, args));
		get(modId).info("[{}] {}", modId, msg);
		fileEnqueue(modId, "CALL", msg);
	}

	/**
	 * 记录一次「注入」：结构化输出 目标类.目标方法 被哪个 Mixin 类注入。
	 *
	 * <pre>{@code
	 * ChasmLogger.injection("mymod", "net.minecraft.world.item.Item", "use", "MixinItem");
	 * // → [INFO] [mymod] INJECT net.minecraft.world.item.Item.use <- MixinItem
	 * }</pre>
	 *
	 * @param modId        模组命名空间
	 * @param targetClass  被注入的目标类（全限定名）
	 * @param targetMethod 被注入的目标方法
	 * @param mixinClass   执行注入的 Mixin 类名
	 */
	public static void injection(String modId, String targetClass, String targetMethod, String mixinClass) {
		String msg = "INJECT " + targetClass + "." + targetMethod + " <- " + mixinClass;
		get(modId).info("[{}] {}", modId, msg);
		fileEnqueue(modId, "INJECT", msg);
	}

	/**
	 * 记录一次「事件」：结构化输出 事件处理类.方法 + 事件名。
	 *
	 * <pre>{@code
	 * ChasmLogger.event("mymod", "ChasmItem", "use", "onRightClick");
	 * // → [INFO] [mymod] EVENT ChasmItem.use onRightClick
	 * }</pre>
	 */
	public static void event(String modId, String className, String methodName, String eventName) {
		String msg = "EVENT " + className + "." + methodName + " " + eventName;
		get(modId).info("[{}] {}", modId, msg);
		fileEnqueue(modId, "EVENT", msg);
	}

	/**
	 * 主动 flush 并关闭会话文件日志器（服务端 STOP 等显式时机可调用）。
	 * 通常无需手动调用——JVM 关闭钩子已代为执行；重复调用安全。
	 */
	public static void flushAndClose() {
		ChasmFileLogger fl = fileLogger;
		if (fl != null) {
			fl.close();
		}
	}

	// —— 内部工具 ——

	/** SLF4J 风格 {@code {}} 占位符渲染成最终字符串。 */
	private static String render(String format, Object... args) {
		return MessageFormatter.arrayFormat(format, args).getMessage();
	}

	/** 将一行日志异步写入会话文件（懒加载文件日志器；任何异常都被吞掉，不影响主流程）。 */
	private static void fileEnqueue(String modId, String level, String message) {
		try {
			ChasmFileLogger fl = fileLogger;
			if (fl == null && !fileInitFailed) {
				fl = fileLogger = new ChasmFileLogger();
			}
			if (fl != null) {
				fl.enqueue(formatFileLine(modId, level, message));
			}
		} catch (Throwable t) {
			// 文件日志不可用时静默降级为仅控制台输出：先标记失败防递归，
			// 再用 raw SLF4J 打印一次根因便于排查（不再经 fileEnqueue，避免二次初始化）。
			fileInitFailed = true;
			fileLogger = null;
			LoggerFactory.getLogger("chasm")
				.error("[chasm-log] 会话文件日志初始化失败(modId={}): {}", modId, String.valueOf(t), t);
		}
	}

	/** 拼装文件日志行：{@code [HH:mm:ss.SSS] [modId] [LEVEL] message}（线程安全，用 LocalTime）。 */
	private static String formatFileLine(String modId, String level, String message) {
		LocalTime now = LocalTime.now();
		String ts = String.format("%02d:%02d:%02d.%03d",
			now.getHour(), now.getMinute(), now.getSecond(), now.getNano() / 1_000_000);
		return "[" + ts + "] [" + modId + "] [" + level + "] " + message;
	}

	/** 把 className/methodName 作为 CALL 占位符的前两个实参插入。 */
	private static Object[] insertPrefixArgs(String className, String methodName, Object... args) {
		Object[] merged = new Object[args.length + 2];
		merged[0] = className;
		merged[1] = methodName;
		System.arraycopy(args, 0, merged, 2, args.length);
		return merged;
	}
}