package api.chasm.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **多消费者挂载**：一份内容喂多个落点，只扫描一次、只解析一次 JSON。
 *
 * <p>这是 2026-09-20 框架收尾新增的能力。它要解决的实机问题有据可查（CodeWiki §49）：
 * 端口的外观管线与模块加载器各自挂一个重载监听器去读同一批 {@code data/tetra/modules/**}，
 * 因为 Fabric 对**同一个监听器 id 只保留第一次**注册（第二次被丢弃）——
 * 症状是"内容源重载完成：78 条成功"与"模块数据：0 个模块"同时出现。</p>
 *
 * <p>测试走 {@link ChasmContent.Builder#buildForTesting()} + {@code deliver}：
 * 与数据包重载**同一条投递路径**，但不需要 Fabric 的资源管理器。</p>
 */
class ChasmContentMultiConsumerTest {

	private static JsonObject json(String body) {
		return JsonParser.parseString(body).getAsJsonObject();
	}

	private static Map<ResourceLocation, JsonObject> documents(String... paths) {
		Map<ResourceLocation, JsonObject> out = new LinkedHashMap<>();
		for (String path : paths) {
			out.put(ResourceLocation.fromNamespaceAndPath("tetra", path), json("{\"value\":\"" + path + "\"}"));
		}
		return out;
	}

	@Test
	void oneSourceFeedsEveryConsumer() {
		List<String> owners = new ArrayList<>();
		List<String> appearances = new ArrayList<>();
		List<ResourceLocation> appearanceIds = new ArrayList<>();

		ChasmContent<String> content = ChasmContent.<String>of("tetra", "modules")
			.parse((id, object) -> Optional.of(object.get("value").getAsString()))
			.target((id, value) -> owners.add(value))
			.onRemove(id -> owners.removeIf(value -> value.endsWith("/" + id.getPath())))
			.buildForTesting();
		// 第二个消费者：自己的解析器 + 自己的落点（外观管线就长这样）
		assertTrue(content.mount(
			(id, object) -> Optional.of(object.get("value").getAsString().toUpperCase(java.util.Locale.ROOT)),
			(id, value) -> {
				appearanceIds.add(id);
				appearances.add(value);
			},
			id -> appearanceIds.remove(id)));

		assertEquals(2, content.consumerCount());

		content.deliver(documents("sword/basic_blade", "sword/basic_hilt"));

		assertEquals(List.of("sword/basic_blade", "sword/basic_hilt"), owners, "拥有者收到全部文件");
		assertEquals(List.of("SWORD/BASIC_BLADE", "SWORD/BASIC_HILT"), appearances, "第二个消费者用自己的解析器");
		assertEquals(2, content.loadedCount(), "loadedCount 仍是第一消费者的口径");
		assertEquals(4, content.totalLoadedCount(), "总数 = 两个消费者之和");
		assertEquals(2, content.loadedIds().size());
	}

	@Test
	void removedFilesAreUnregisteredForEachConsumerSeparately() {
		List<ResourceLocation> owners = new ArrayList<>();
		List<ResourceLocation> appearances = new ArrayList<>();
		ChasmContent<String> content = ChasmContent.<String>of("tetra", "materials")
			.parse((id, object) -> Optional.of("x"))
			.target((id, value) -> owners.add(id))
			.onRemove(owners::remove)
			.buildForTesting();
		content.mount((id, object) -> Optional.of("y"), (id, value) -> appearances.add(id), appearances::remove);

		content.deliver(documents("metal/iron", "metal/copper"));
		assertEquals(2, owners.size());
		assertEquals(2, appearances.size());

		// 第二轮只留一个文件：两个消费者都必须各自注销消失的那个 id（不然注册表里留幽灵）
		content.deliver(documents("metal/iron"));
		assertEquals(List.of(ResourceLocation.fromNamespaceAndPath("tetra", "metal/iron")), owners);
		assertEquals(List.of(ResourceLocation.fromNamespaceAndPath("tetra", "metal/iron")), appearances);
	}

	@Test
	void oneConsumerFailingDoesNotBlockTheOther() {
		List<String> ok = new ArrayList<>();
		ChasmContent<String> content = ChasmContent.<String>of("tetra", "modules")
			.parse((id, object) -> Optional.of("owner"))
			.target((id, value) -> ok.add(value))
			.buildForTesting();
		content.mount((id, object) -> {
			throw new IllegalStateException("这个消费者固定的解析异常");
		}, (id, value) -> {
		}, null);

		content.deliver(documents("a", "b"));
		assertEquals(2, ok.size(), "坏消费者只影响自己（框架逐消费者隔离异常）");
	}

	@Test
	void duplicateMountOfSameConsumerIsRejected() {
		ChasmContent<String> content = ChasmContent.<String>of("tetra", "modules")
			.parse((id, object) -> Optional.of("x"))
			.target((id, value) -> {
			})
			.buildForTesting();
		java.util.function.BiFunction<ResourceLocation, JsonObject, Optional<String>> parser =
			(id, object) -> Optional.of("y");
		List<String> sink = new ArrayList<>();
		java.util.function.BiConsumer<ResourceLocation, String> target = (id, value) -> sink.add(value);
		assertTrue(content.mount(parser, target, null));
		assertFalse(content.mount(parser, target, null), "同一个 parser+target 挂两次 = 重复投递，必须挡掉");
		assertEquals(2, content.consumerCount());
	}

	@Test
	void mountBeforeOwnerRegisterIsQueuedNotLost() {
		ChasmContent.clearRegistryForTesting();
		int pending = ChasmContent.pendingCount();
		// 拥有者还不存在：先挂载 → 进待挂载队列（顺序无关的关键）
		assertFalse(ChasmContent.mountExisting("mymod", "late",
			(id, object) -> Optional.of("value"), (id, value) -> {
			}, null));
		assertEquals(pending + 1, ChasmContent.pendingCount());
		ChasmContent.clearRegistryForTesting();
		assertEquals(0, ChasmContent.pendingCount());
	}

	@Test
	void dataRegistryMountKeepsItsOwnRecall() {
		ChasmDataRegistry<String> registry = ChasmDataRegistry.create("test/consumers");
		// mount 一个还不存在的内容源：本表不被清空、也不会抛异常（拥有者注册时才接上）
		assertNotNull(registry.mount("tetra", "not_registered_yet",
			(id, object) -> Optional.of("v")));
		registry.accept(ResourceLocation.fromNamespaceAndPath("tetra", "manual"), "manual");
		assertEquals("manual", registry.getByPath("manual"));
		assertEquals(1, registry.size());
	}
}
