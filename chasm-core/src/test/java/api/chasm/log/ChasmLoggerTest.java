package api.chasm.log;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 纯 JVM 单测：{@link ChasmLogger} 统一日志门面。
 *
 * <p>覆盖：调试模式开关、按 modId 的 Logger 懒加载与缓存、结构化方法
 * （info/warn/error/call/injection/event）在会话文件日志器可用/不可用两种
 * 情形下都不抛异常（日志系统绝不影响主流程）。</p>
 */
class ChasmLoggerTest {

	@AfterAll
	static void tearDown() {
		// 释放会话文件写入线程，避免残留守护线程
		ChasmLogger.flushAndClose();
	}

	@Test
	void debugModeToggles() {
		ChasmLogger.disableDebug();
		assertFalse(ChasmLogger.isDebugEnabled());
		ChasmLogger.enableDebug();
		assertTrue(ChasmLogger.isDebugEnabled());
		ChasmLogger.setDebugMode(false);
		assertFalse(ChasmLogger.isDebugEnabled());
	}

	@Test
	void getReturnsCachedLoggerPerModId() {
		assertNotNull(ChasmLogger.get("mymod"));
		assertSame(ChasmLogger.get("mymod"), ChasmLogger.get("mymod"),
			"同一 modId 应返回缓存的同一 Logger");
	}

	@Test
	void structuredMethodsDoNotThrow() {
		ChasmLogger.enableDebug();
		assertDoesNotThrow(() -> ChasmLogger.info("mymod", "hello {} {}", 1, "x"));
		assertDoesNotThrow(() -> ChasmLogger.warn("mymod", "warn {}", 1));
		assertDoesNotThrow(() -> ChasmLogger.error("mymod", "error {}", 1));
		assertDoesNotThrow(() -> ChasmLogger.debug("mymod", "debug {}", 1));
		assertDoesNotThrow(() -> ChasmLogger.call("mymod", "MyClass", "doThing", "detail {} 次", 5));
		assertDoesNotThrow(() -> ChasmLogger.injection("mymod", "net.minecraft.world.item.Item", "use", "MixinItem"));
		assertDoesNotThrow(() -> ChasmLogger.event("mymod", "MyClass", "use", "onRightClick"));
	}

	@Test
	void flushAndCloseIsSafe() {
		assertDoesNotThrow(ChasmLogger::flushAndClose);
		assertDoesNotThrow(ChasmLogger::flushAndClose);
	}
}
