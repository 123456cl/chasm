package api.chasm.log;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Chasm 会话文件日志器（阶段 0 + 2：会话目录 + 时间戳 + 文件路径管理）。
 *
 * <p>每次游戏启动创建一个独立会话目录：</p>
 * <pre>{@code
 * <游戏目录>/chasm-logs/yyyy-MM-dd_HH-mm-ss/session.log
 * }</pre>
 *
 * <p>{@link ChasmLogger} 只做 API 门面，实际的目录/文件/异步写入由本类承担。
 * 构造时注册一个 JVM 关闭钩子，确保游戏退出前 {@link #close()} 被调用以 flush 日志。</p>
 */
public final class ChasmFileLogger implements AutoCloseable {

	/** 日志根目录名（相对游戏运行目录）。 */
	private static final String LOG_ROOT = "chasm-logs";

	private final Path sessionDir;
	private final AsyncLogWriter writer;
	private final long startTime;

	/**
	 * 创建一次会话的文件日志器：在游戏目录下建立带时间戳的会话文件夹并打开 session.log，
	 * 同时注册 JVM 关闭钩子以便退出时 flush。
	 */
	public ChasmFileLogger() {
		this.startTime = System.currentTimeMillis();
		String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(startTime));
		Path gameDir = FabricLoader.getInstance().getGameDir();
		this.sessionDir = gameDir.resolve(LOG_ROOT).resolve(timestamp);
		this.writer = new AsyncLogWriter(sessionDir.resolve("session.log"));
		Runtime.getRuntime().addShutdownHook(new Thread(this::close, "chasm-log-shutdown"));
		// 用 raw SLF4J 打印实际落盘路径（避免经 ChasmLogger 递归触发文件初始化）
		org.slf4j.LoggerFactory.getLogger("chasm")
			.info("[chasm-log] 会话日志目录已创建: {} (gameDir={})", sessionDir, gameDir);
	}

	/** 当前会话目录（含时间戳）。 */
	public Path sessionDir() {
		return sessionDir;
	}

	/** 会话启动时刻（毫秒）。 */
	public long startTime() {
		return startTime;
	}

	/** 把一行日志入队写入 session.log（异步，不阻塞）。 */
	public void enqueue(String line) {
		writer.enqueue(line);
	}

	/** 关闭会话文件日志器（flush + 关闭文件）。重复调用安全。 */
	@Override
	public void close() {
		writer.close();
	}
}