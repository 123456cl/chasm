package api.chasm.log;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 纯 JVM 单测：{@link AsyncLogWriter} 异步日志写入器。
 *
 * <p>覆盖：嵌套父目录自动创建、入队行落盘、close 后剩余行排空并 flush、
 * 关闭后 enqueue 为 no-op、close 幂等。</p>
 */
class AsyncLogWriterTest {

	@Test
	void writesQueuedLinesAndCreatesNestedParent() throws Exception {
		Path dir = Files.createTempDirectory("chasm-log-test");
		// 嵌套父目录：验证构造时自动创建
		Path file = dir.resolve("sub").resolve("session.log");
		try (AsyncLogWriter writer = new AsyncLogWriter(file)) {
			writer.enqueue("line one");
			writer.enqueue("line two");
			writer.enqueue("line three");
		}
		assertTrue(Files.exists(file), "日志文件应被创建");
		String content = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(content.contains("line one"), "第一行应落盘");
		assertTrue(content.contains("line two"), "第二行应落盘");
		assertTrue(content.contains("line three"), "第三行应落盘");
		// 每行应带换行
		assertTrue(content.lines().count() >= 3, "每行应换行分隔");

		Files.deleteIfExists(file);
		Files.deleteIfExists(file.getParent());
		Files.deleteIfExists(dir);
	}

	@Test
	void enqueueAfterCloseIsNoopAndCloseIsIdempotent() throws Exception {
		Path dir = Files.createTempDirectory("chasm-log-test2");
		Path file = dir.resolve("session.log");
		AsyncLogWriter writer = new AsyncLogWriter(file);
		writer.enqueue("before close");
		writer.close();
		writer.enqueue("after close"); // 关闭后入队应被忽略，不得抛异常
		writer.close(); // 重复 close 安全

		String content = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(content.contains("before close"), "关闭前入队的行应被排空落盘");
		assertFalse(content.contains("after close"), "关闭后入队的行应被忽略");

		Files.deleteIfExists(file);
		Files.deleteIfExists(dir);
	}
}
