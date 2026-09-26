package api.chasm.registry;

import api.chasm.Chasm;
import api.chasm.ChasmMod;
import api.chasm.context.ChasmContextRegistry;
import api.chasm.context.ModContext;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **内容容器（{@link ChasmContentHolder}）注册语义的回归测试**。
 *
 * <p>钉死三件事：</p>
 * <ol>
 *   <li>容器里的 {@code @Register} 声明与入口类进的是**同一个 mod 上下文**（同 modId、同 {@link ModContext}），
 *       所以 SPI 暴露、其它模组的扩展点、DataGen 收集都不会因为拆类而改变；</li>
 *   <li>用错时给**说得清的错误**：忘注解、把声明写成实例字段 —— 都在扫描期报错，
 *       而不是等到运行时 {@code field.get(null)} 抛 NPE；</li>
 *   <li>入口类自己的扫描语义没被这次重构改掉（同一个类里 {@code @ChasmMod} 路径照旧）。</li>
 * </ol>
 *
 * <p>测试用 {@code registerToRegistry=false} 的"收集模式"跑（与 DataGen 同一条路径）：
 * 测试 JVM 里注册表可能已冻结，收集模式不碰原版注册表，只验证"声明进入上下文"这一条。</p>
 */
class ChasmContentHolderTest {

	/** 假入口类：只有它带 {@code @ChasmMod}。 */
	@ChasmMod(id = "chasmtest_holder")
	static final class FakeMod {
		@Register("entry_item")
		static final Item ENTRY_ITEM = Chasm.item().name("Entry").register();
	}

	/** 假内容容器：声明注册到 FakeMod 的同一个 mod 下。 */
	@ChasmContentHolder("chasmtest_holder")
	static final class FakeContent {
		@Register("content_item")
		static final Item CONTENT_ITEM = Chasm.item().name("Content").register();
	}

	/** 反例一：声明写成了实例字段（扫描器按类读字段，读不到）。 */
	@ChasmContentHolder("chasmtest_holder")
	static final class InstanceFieldContent {
		@Register("instance_item")
		final Item INSTANCE_ITEM = Chasm.item().name("Instance").register();
	}

	/** 反例二：忘了打注解。 */
	static final class NotAContentHolder {
		@Register("ignored_item")
		static final Item IGNORED_ITEM = Chasm.item().name("Ignored").register();
	}

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void contentHolderLandsInTheSameModContextAsTheEntryClass() {
		ChasmRegistrar.scan(FakeMod.class, false, false);
		ChasmRegistrar.scanContent(FakeContent.class, false, false);

		ModContext ctx = ChasmContextRegistry.get("chasmtest_holder");
		assertNotNull(ctx.item("entry_item"), "入口类的声明照旧进上下文");
		assertNotNull(ctx.item("content_item"), "容器的声明进的是**同一个** mod 上下文");
		assertSame(FakeContent.CONTENT_ITEM, ctx.item("content_item").item(),
			"上下文里挂的就是容器构建出来的那个物品实例（没有重新构造一份）");
	}

	@Test
	void instanceFieldDeclarationsAreRejectedWhileScanning() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> ChasmRegistrar.scanContent(InstanceFieldContent.class, false, false));
		assertTrue(e.getMessage().contains("static"), "错误信息要说清是 static 的问题: " + e.getMessage());
		assertTrue(e.getMessage().contains("INSTANCE_ITEM"), "错误信息要指出字段名: " + e.getMessage());
	}

	@Test
	void classesWithoutTheAnnotationAreRejectedWithGuidance() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> ChasmRegistrar.scanContent(NotAContentHolder.class, false, false));
		assertTrue(e.getMessage().contains("@ChasmContentHolder"), "错误信息要指出缺哪个注解: " + e.getMessage());
	}
}
