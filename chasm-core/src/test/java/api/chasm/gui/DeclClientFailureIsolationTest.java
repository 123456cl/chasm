package api.chasm.gui;

import api.chasm.Chasm;
import api.chasm.gui.decl.client.DeclClient;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **界面数据出错不许崩客户端**（真实事故的回归测试）。
 *
 * <p>2026-09-18 实测：一个空 key 进了 {@code ConcurrentHashMap.get(null)}，异常从
 * {@code source.build} 一路冒到 {@code GameRenderer.render}，客户端**整个崩溃退出**（两次，
 * 用户损失两次重启）。{@code source.build} 跑在渲染线程上，界面数据的小 bug 不该有这个代价。</p>
 *
 * <p>修法：在界面边界把异常拦下 —— 打完整堆栈、这一帧渲染空列表。想照旧直接崩（开发定位期），
 * 打开 {@code DeclClient.strictBuildFailures}。</p>
 */
class DeclClientFailureIsolationTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void throwingSourceIsContainedInsteadOfCrashingTheClient() {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("chasmtest", "boom_screen");
		Chasm.gui("chasmtest", "boom_screen")
			.panelSize(120, 120)
			.source(state -> {
				throw new NullPointerException("故意制造的界面数据错误（模拟真实崩溃）");
			})
			.register();

		// menu 传 null：故意让构建路径尽量早地进入"异常"分支，验证它不会往外冒
		assertDoesNotThrow(() -> DeclClient.nodes(id, null),
			"界面构建抛异常时必须被拦在界面边界内：抛到渲染线程顶层 = 客户端崩溃");
		assertTrue(DeclClient.nodes(id, null).isEmpty(), "拦下之后这一帧渲染空列表");

		// 第二次调用（清了帧缓存）也不能抛：说明"只报一次"的分支同样安全
		DeclClient.endRenderFrame(id);
		assertDoesNotThrow(() -> DeclClient.nodes(id, null));
	}

	@Test
	void strictModeStillThrowsSoBugsCanBeHunted() {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("chasmtest", "boom_strict");
		Chasm.gui("chasmtest", "boom_strict")
			.panelSize(120, 120)
			.source(state -> {
				throw new NullPointerException("strict 模式必须原样抛出");
			})
			.register();

		DeclClient.strictBuildFailures = true;
		try {
			boolean threw = false;
			try {
				DeclClient.nodes(id, null);
			} catch (RuntimeException expected) {
				threw = true;
			}
			assertTrue(threw, "打开 strictBuildFailures 后必须原样抛异常（否则开发期看不到 bug）");
		} finally {
			DeclClient.strictBuildFailures = false;
		}
	}
}
