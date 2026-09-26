package api.chasm.gui;

import api.chasm.Chasm;
import api.chasm.gui.decl.GuiDeclarations;
import api.chasm.gui.decl.GuiNode;
import api.chasm.gui.decl.GuiProbe;
import api.chasm.gui.decl.GuiState;
import api.chasm.gui.decl.client.DeclClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **UI 探针**（{@link GuiProbe}）的离线测试：不碰客户端、不碰文件，只看它生成的 JSONL。
 *
 * <h2>断言的是四件事</h2>
 * <ol>
 *   <li><b>默认关闭</b>：{@link GuiProbe#enabled()} 为 false，{@link GuiProbe#onDraw} 一行都不记
 *       —— "关掉时行为逐字不变"的第一道证据（第二道是全部既有 GUI 测试照旧全绿）。</li>
 *   <li><b>只在状态变化时写一行</b>：同一状态重复调用 → {@code null}（不写），状态变了才写；
 *       序号自增、带时间戳。</li>
 *   <li><b>{@code every} 模式每次绘制都写</b>：同一状态连续三次 → 三行。</li>
 *   <li><b>行内容 = 真实节点</b>：用 Gson 解析 dump 行，逐字段与**真实 {@link GuiNode}** 比对
 *       （key/rect/layer/text/action/value/slot/enabled/贴图 UV），并检查 gui id、全部状态键、
 *       节点数组长度、可交互接口导出。</li>
 * </ol>
 */
class GuiProbeTest {

	/** 带动作注册的界面 id（handler 导出的断言用它）。 */
	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("chasmtest", "gui_probe_nodes");
	/** **从不注册任何动作**的界面 id：用于断言"查不到 handler 就如实写 null"（不受别的测试注册影响）。 */
	private static final ResourceLocation BARE_ID =
		ResourceLocation.fromNamespaceAndPath("chasmtest", "gui_probe_nodes_bare");

	private static ChasmGui gui;
	private static ChasmGui bareGui;

	@BeforeAll
	static void setUp() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		gui = declare("gui_probe_nodes");
		bareGui = declare("gui_probe_nodes_bare");
	}

	/** 两个界面共用同一份内容源：{@code a} 变化会同时改变文字与节点集合（多一个 sprite）。 */
	private static ChasmGui declare(String path) {
		return Chasm.gui("chasmtest", path)
			.panelSize(120, 60)
			.data("a", 0, 0, 3)
			.data("b", -1, -1, 7)
			.source(GuiProbeTest::build)
			.register();
	}

	private static List<GuiNode> build(GuiState state) {
		int a = state.hasData("a") ? state.data("a") : 0;
		List<GuiNode> nodes = new ArrayList<>();
		nodes.add(GuiNode.fill("bg", 0, 0, 120, 60, 0xFF202020).behind());
		nodes.add(GuiNode.of("hit", 4, 4, 20, 10).action("go", a).hover(0xFFFFCC).tooltip("点我"));
		nodes.add(GuiNode.of("label", 2, 2, 40, 8).text("A=" + a, 0xFFFFFF));
		if (a > 0) {
			nodes.add(GuiNode.sprite("icon", 30, 4, 8, 8,
				ResourceLocation.parse("minecraft:textures/item/diamond.png"), 0, 0, 16, 16));
		}
		return nodes;
	}

	private static ChasmMenu menu(ChasmGui target) {
		return new ChasmMenu(0, new Inventory(null), target, true);
	}

	/** 一帧的真实节点列表（与 {@code ChasmScreen.render} 同一入口：先开新帧再取列表）。 */
	private static List<GuiNode> frame(ResourceLocation id, ChasmMenu menu) {
		DeclClient.startRenderFrame(id);
		return DeclClient.nodes(id, menu);
	}

	// ------------------------------------------------------------------ 1. 默认关闭

	@Test
	void probeIsOffByDefaultAndRecordsNothing() {
		assertFalse(GuiProbe.enabled(), "探针默认必须是关闭的（-Dchasm.gui.probe.file 才会打开）");
		assertFalse(GuiProbe.everyDraw(), "默认模式是 change（只在状态变化时写）");
		assertNull(GuiProbe.file(), "默认不配置 dump 文件");
		assertEquals(0, GuiProbe.activeLineCount(), "关闭时一行都不许记");

		ChasmMenu menu = menu(bareGui);
		List<GuiNode> nodes = frame(BARE_ID, menu);
		// 关闭时 onDraw 是空实现：不抛、不记、也不碰动画状态
		GuiProbe.onDraw(BARE_ID, menu, nodes, 0, 0);
		GuiProbe.onDraw(BARE_ID, null, null, 0, 0);
		assertEquals(0, GuiProbe.activeLineCount(), "关闭状态下调用钩子必须零副作用");
	}

	// ------------------------------------------------------------------ 2. 状态变化才写

	@Test
	void oneLinePerStateChangeOnly() {
		GuiProbe.Recorder recorder = new GuiProbe.Recorder();
		ChasmMenu menu = menu(bareGui);

		List<GuiNode> first = frame(BARE_ID, menu);
		String line = recorder.record(BARE_ID, menu, first, 10, 20, false);
		assertNotNull(line, "第一次看到这个界面必须写一行");
		assertEquals(1, recorder.lineCount());

		// 同一状态再画一帧：不写（否则每帧刷屏）
		assertNull(recorder.record(BARE_ID, menu, first, 10, 20, false), "状态没变就不该写第二行");
		assertEquals(1, recorder.lineCount(), "重复状态不许产生新行");

		// 状态变化 → 写第二行
		menu.setData("a", 2);
		List<GuiNode> second = frame(BARE_ID, menu);
		String changed = recorder.record(BARE_ID, menu, second, 10, 20, false);
		assertNotNull(changed, "状态变了必须写一行");
		assertEquals(2, recorder.lineCount());

		JsonObject firstJson = JsonParser.parseString(line).getAsJsonObject();
		JsonObject secondJson = JsonParser.parseString(changed).getAsJsonObject();
		assertEquals(1, firstJson.get("seq").getAsInt());
		assertEquals(2, secondJson.get("seq").getAsInt());
		assertTrue(firstJson.get("ts").getAsLong() > 0, "每行必须带时间戳");
		assertEquals("change", secondJson.get("mode").getAsString());
		// 变化标记：第一行 = 全部键（刚出现），第二行只有 a
		assertEquals(List.of("a", "b"), names(firstJson.getAsJsonArray("stateChanged")));
		assertEquals(List.of("a"), names(secondJson.getAsJsonArray("stateChanged")));
		// 节点级差量：a>0 多了一个 sprite 节点，且 hit 的 action value 变了
		JsonObject delta = secondJson.getAsJsonObject("delta");
		assertTrue(names(delta.getAsJsonArray("added")).contains("icon"), "新出现的节点必须在 delta.added 里");
		assertTrue(names(delta.getAsJsonArray("changed")).contains("hit"), "value 变了要在 delta.changed 里");
		assertTrue(names(delta.getAsJsonArray("removed")).isEmpty());
		assertTrue(firstJson.getAsJsonObject("delta").get("first").getAsBoolean(),
			"某界面的第一行用 delta.first 标记（没有上一帧可比）");
	}

	// ------------------------------------------------------------------ 3. every 模式

	@Test
	void everyDrawModeWritesEverySingleFrame() {
		GuiProbe.Recorder recorder = new GuiProbe.Recorder();
		ChasmMenu menu = menu(bareGui);
		List<GuiNode> nodes = frame(BARE_ID, menu);

		assertNotNull(recorder.record(BARE_ID, menu, nodes, 0, 0, true));
		assertNotNull(recorder.record(BARE_ID, menu, nodes, 0, 0, true));
		assertNotNull(recorder.record(BARE_ID, menu, nodes, 0, 0, true));
		assertEquals(3, recorder.lineCount(), "every 模式：每次绘制都写一行（状态没变也写）");
		assertEquals(3, recorder.lastSeq());
		JsonObject third = JsonParser.parseString(recorder.lines().get(2)).getAsJsonObject();
		assertEquals(3, third.get("seq").getAsInt());
		assertEquals("every", third.get("mode").getAsString());
		// 三行的 body 完全相同（同一状态）→ hash 相同，只有 seq 不同
		assertEquals(JsonParser.parseString(recorder.lines().get(0)).getAsJsonObject().get("hash").getAsString(),
			third.get("hash").getAsString(), "同一状态的 dump body 必须逐字一致（diff 才有意义）");
	}

	// ------------------------------------------------------------------ 4. 行内容 = 真实节点

	@Test
	void lineCarriesGuiIdStateKeysAndEveryRealNode() {
		GuiProbe.Recorder recorder = new GuiProbe.Recorder();
		ChasmMenu menu = menu(bareGui);
		menu.setData("a", 1);
		List<GuiNode> nodes = frame(BARE_ID, menu);

		String line = recorder.record(BARE_ID, menu, nodes, 10, 20, false);
		assertNotNull(line);
		JsonObject json = JsonParser.parseString(line).getAsJsonObject();

		assertEquals(GuiProbe.SCHEMA, json.get("schema").getAsString());
		assertEquals("chasmtest:gui_probe_nodes_bare", json.get("gui").getAsString());
		assertEquals(10, json.getAsJsonArray("origin").get(0).getAsInt());
		assertEquals(20, json.getAsJsonArray("origin").get(1).getAsInt());
		assertEquals(120, json.getAsJsonArray("panel").get(0).getAsInt());

		// 全部状态键（int 通道）+ 声明（默认值/min/max）
		JsonObject state = json.getAsJsonObject("state");
		assertEquals(List.of("a", "b"), new ArrayList<>(state.keySet()));
		assertEquals(1, state.get("a").getAsInt());
		assertEquals(-1, state.get("b").getAsInt());
		JsonArray decl = json.getAsJsonObject("stateDecl").getAsJsonArray("a");
		assertEquals(0, decl.get(0).getAsInt());
		assertEquals(3, decl.get(2).getAsInt());

		// 每个节点：字段必须与真实 GuiNode 逐个一致
		JsonArray dumped = json.getAsJsonArray("nodes");
		assertEquals(nodes.size(), dumped.size(), "节点数组必须与真实节点列表等长");
		assertEquals(nodes.size(), json.get("nodesCount").getAsInt());
		for (int i = 0; i < nodes.size(); i++) {
			assertNodeMatches(nodes.get(i), dumped.get(i).getAsJsonObject(), menu);
		}

		// 可交互接口：hit 节点带动作 go#1；本界面**没有**注册 handler → 如实写 false/null
		assertNull(GuiDeclarations.actionOf(BARE_ID, "go"), "本测试用的界面不许注册任何动作");
		JsonArray interactive = json.getAsJsonArray("interactive");
		assertEquals(1, interactive.size(), "只有 hit 带动作");
		JsonObject hit = interactive.get(0).getAsJsonObject();
		assertEquals("hit", hit.get("node").getAsString());
		assertEquals("go", hit.get("action").getAsString());
		assertEquals(1, hit.get("value").getAsInt());
		assertFalse(hit.get("handlerRegistered").getAsBoolean(), "没注册的动作必须如实写 false");
		assertTrue(hit.get("handler").isJsonNull(), "查不到 handler → null（绝不编名字）");
		assertEquals(4, hit.getAsJsonArray("rect").get(0).getAsInt());
	}

	/** 注册了 handler 的动作，dump 里必须给出 handler 的**声明类**。 */
	@Test
	void registeredHandlerIsExportedByItsDeclaringClass() {
		GuiDeclarations.register(ID, GuiProbeTest::build, Map.of("go", (player, value, state) -> {
		}));
		GuiProbe.Recorder recorder = new GuiProbe.Recorder();
		ChasmMenu menu = menu(gui);
		String line = recorder.record(ID, menu, frame(ID, menu), 0, 0, false);
		assertNotNull(line);
		JsonObject json = JsonParser.parseString(line).getAsJsonObject();
		JsonArray interactive = json.getAsJsonArray("interactive");
		assertEquals(1, interactive.size(), "ID 的动作表里有 go，而 hit 节点就挂 go");
		JsonObject hit = interactive.get(0).getAsJsonObject();
		assertTrue(hit.get("handlerRegistered").getAsBoolean(), "动作在注册表里 → true");
		assertTrue(hit.get("handler").getAsString().startsWith("api.chasm.gui.GuiProbeTest"),
			"handler 名 = 声明类（lambda 去掉 $$Lambda$…），实际 " + hit.get("handler"));
		assertTrue(hit.get("handlerClass").getAsString().contains("GuiProbeTest"));
	}

	// ------------------------------------------------------------------ 公用件

	private static void assertNodeMatches(GuiNode node, JsonObject dumped, ChasmMenu menu) {
		GuiRect rect = node.rect(menu);
		String where = "节点 " + node.key();
		assertEquals(node.key(), dumped.get("key").getAsString(), where);
		assertEquals(node.layer().name(), dumped.get("layer").getAsString(), where);
		JsonArray r = dumped.getAsJsonArray("rect");
		assertEquals(rect.x(), r.get(0).getAsInt(), where + " rect.x");
		assertEquals(rect.y(), r.get(1).getAsInt(), where + " rect.y");
		assertEquals(rect.width(), r.get(2).getAsInt(), where + " rect.w");
		assertEquals(rect.height(), r.get(3).getAsInt(), where + " rect.h");
		assertEquals(node.text(), text(dumped.get("text")), where + " text");
		assertEquals(node.action(), text(dumped.get("action")), where + " action");
		assertEquals(node.value(), dumped.get("value").getAsInt(), where + " value");
		assertEquals(node.slotIndex(), dumped.get("slot").getAsInt(), where + " slot");
		assertEquals(node.inventoryIndex(), dumped.get("invSlot").getAsInt(), where + " invSlot");
		assertEquals(node.isEnabled(), dumped.get("enabled").getAsBoolean(), where + " enabled");
		assertEquals(node.tooltip(), text(dumped.get("tooltip")), where + " tooltip");
		assertEquals(node.texture() == null ? null : node.texture().toString(),
			text(dumped.get("texture")), where + " texture");
		JsonArray uv = dumped.getAsJsonArray("uv");
		assertEquals(node.u(), uv.get(0).getAsInt(), where + " u");
		assertEquals(node.v(), uv.get(1).getAsInt(), where + " v");
		assertEquals(node.texWidth(), uv.get(2).getAsInt(), where + " texW");
		assertEquals(node.texHeight(), uv.get(3).getAsInt(), where + " texH");
		String expectedType = node.slotIndex() >= 0 ? "slot"
			: node.inventoryIndex() >= 0 ? "inventorySlot"
			: node.texture() != null ? (node.isScaled() ? "spriteRegion"
				: (node.u() == 0 && node.v() == 0 && node.texWidth() == node.width()
					&& node.texHeight() == node.height() ? "image" : "sprite"))
			: node.isFill() ? "fill" : node.text() != null ? "text" : "rect";
		assertEquals(expectedType, dumped.get("type").getAsString(), where + " type");
	}

	private static String text(JsonElement element) {
		return element == null || element.isJsonNull() ? null : element.getAsString();
	}

	private static List<String> names(JsonArray array) {
		List<String> keys = new ArrayList<>();
		array.forEach(element -> keys.add(element.getAsString()));
		return keys;
	}
}
