package api.chasm.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Chasm 异步日志写入器（阶段 1：把同步 IO 改为异步，避免磁盘写入卡顿游戏）。
 *
 * <p>内部持有一个后台守护线程 + 无锁 {@link ConcurrentLinkedQueue}：</p>
 * <ul>
 *   <li>{@link #enqueue(String)}：调用方把完整日志行入队，立即返回（不阻塞主线程）</li>
 *   <li>后台线程持续取行并写入 `BufferedWriter`，空闲时周期性 flush</li>
 *   <li>{@link #close()}：置停止标志、打断线程、排空剩余行、flush 并关闭文件</li>
 * </ul>
 *
 * <p>线程为 daemon，配合 {@link ChasmFileLogger} 注册的 JVM 关闭钩子排队 join，
 * 保证游戏退出前剩余日志不丢失。</p>
 */
public final class AsyncLogWriter implements AutoCloseable {

	private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
	private final Thread thread;
	private final BufferedWriter writer;
	private volatile boolean running = true;

	/**
	 * 打开一个日志文件并启动后台写入线程。
	 *
	 * @param file 目标文件路径（父目录不存在会自动创建）
	 * @throws IllegalStateException 无法创建/打开文件时抛出（日志文件不可用）
	 */
	public AsyncLogWriter(Path file) {
		try {
			Files.createDirectories(file.getParent());
			this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new IllegalStateException("Chasm 日志文件无法打开: " + file, e);
		}
		this.thread = new Thread(this::run, "chasm-async-log-writer");
		this.thread.setDaemon(true);
		this.thread.start();
	}

	/** 后台线程主循环：取行写入，空闲时 flush + 短暂休眠。 */
	private void run() {
		while (running) {
			String line = queue.poll();
			if (line != null) {
				writeLine(line);
			} else {
				flush();
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		// 关闭前排空剩余行
		String line;
		while ((line = queue.poll()) != null) {
			writeLine(line);
		}
		flush();
		try {
			writer.close();
		} catch (IOException ignored) {
			// 关闭阶段忽略写文件异常
		}
	}

	private void writeLine(String line) {
		try {
			writer.write(line);
			writer.newLine();
		} catch (IOException e) {
			// 写文件失败不抛给调用方，仅丢弃该行（日志系统绝不影响游戏主流程）
			System.err.println("[chasm-log] 写入日志失败: " + e.getMessage());
		}
	}

	private void flush() {
		try {
			writer.flush();
		} catch (IOException ignored) {
			// 忽略 flush 异常
		}
	}

	/**
	 * 把一行日志入队（即时返回，不阻塞）。调用方务必传入完整、已格式化的行。
	 *
	 * @param line 完整日志行（不含换行符）
	 */
	public void enqueue(String line) {
		if (running) {
			queue.add(line);
		}
	}

	/**
	 * 停止写入：排空队列、flush 并关闭文件。重复调用安全。
	 */
	@Override
	public void close() {
		if (!running) {
			return;
		}
		running = false;
		thread.interrupt();
		try {
			thread.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}