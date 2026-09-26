package api.chasm.gui.decl;

import api.chasm.gui.ChasmGuiState;
import api.chasm.gui.ChasmMenu;
import api.chasm.gui.ChasmStorageSlot;
import api.chasm.gui.GuiRect;
import api.chasm.log.ChasmLogger;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **界面探针**：把"这一帧的界面长什么样"按 JSONL 一行一帧 dump 到文件。
 *
 * <p>它是 {@code ui = f(state)} 范式的直接产物：节点列表建好之后、绘制之前，
 * 框架手里已经握着**这一帧的全部真相**（界面 id、同步状态、每个节点的几何/文字/贴图/动作），
 * 所以探针不需要截图、不需要 OCR、不需要猜 —— 直接把那份数据序列化出来。</p>
 *
 * <h2>它解决什么问题</h2>
 * <p>移植真实模组的界面时，"画错了什么"很难逐条列举。把**真实版**与**移植版**各自装同一套探针，
 * 两边产出的 JSONL 同格式 dump 一 diff，"同一界面 id 下同一状态键 → 节点集合"的差集
 * 就是施工清单（缺了哪个元素、坐标差几像素、文字来自哪个语言键、哪个槽位该空却没空）。</p>
 *
 * <h2>开关（默认关闭 = 一行都不写、零副作用）</h2>
 * <pre>
 * -Dchasm.gui.probe.file=/path/to/dump.jsonl   写这个文件（中文/空格路径用引号）
 * -Dchasm.gui.probe.mode=every                 "每次绘制都写"（默认 change = 只在状态变化时写）
 * -Dchasm.gui.probe=on                         等价于只给 file 不给路径（默认 chasm-gui-probe.jsonl）
 * 环境变量：CHASM_GUI_PROBE_FILE / CHASM_GUI_PROBE_MODE / CHASM_GUI_PROBE
 * </pre>
 * <p>关掉时：{@link #ENABLED} 是 {@code false} 的 {@code static final} 字段，
 * {@link DeclClient} 里的那一行是 {@code if (GuiProbe.ENABLED) ...} —— JIT 直接把它当死代码消掉；
 * 而且探针**只读**节点与状态，从不碰动画状态（{@code ChasmGuiAnimations.smooth/entry} 会推进时间戳，
 * 调它就会改变画面），所以关闭时行为与加探针之前**逐字相同**。</p>
 *
 * <h2>一行里有什么（schema {@value #SCHEMA}）</h2>
 * <pre>
 * {"schema":"chasm.gui.probe/1",
 *  "seq":1,"ts":1758000000000,"mode":"change","hash":"3a1b2c4d",
 *  "stateChanged":["modules","group"],                  // 状态键的变化标记（首次出现 = 全部）
 *  "delta":{"added":["grow:short_blade"],"removed":[],"changed":["craft_bg"]},
 *  "gui":"tetra:workbench","title":"加工台","origin":[0,0],"panel":[320,240],
 *  "menu":{"syncId":0,"clientSide":true,"bound":false,"storageSlots":4,"playerSlots":36},
 *  "sem":{...},                                          // 界面级语义（由注册的 LineFacts 提供）
 *  "stateDecl":{"modules":[-1,-1,4]},                    // [默认值, min, max]（声明，不是当前值）
 *  "state":{"modules":0,...},                            // 全部 int 键的当前值
 *  "stateValues":{...},                                  // 全部大值键（toString，截断 400 字）
 *  "slots":[{"i":0,"x":152,"y":58,"active":true,"empty":true,"item":null,...}],
 *  "nodes":[{"i":0,"key":"...","type":"sprite","draw":["sprite"],"rect":[x,y,w,h],...}],
 *  "interactive":[{"node":"craft_bg","action":"craft","value":0,"handler":"...","rect":[...]}],
 *  "nodesCount":123}
 * </pre>
 *
 * <h3>节点字段</h3>
 * <p>{@code key} / {@code type} / {@code draw[]}（这一帧真的会画哪几遍：fill/sprite/text）/
 * {@code layer}（BEHIND=物品之前、ABOVE=物品之后）/ {@code rect}（**面板像素**，就是
 * {@link GuiNode#rect} 的返回）/ {@code texture,uv[4],src[2],scaled,tint,fill} /
 * {@code text,textScale,shadow,color,hover,opacity,delay,enabled,tooltip} /
 * {@code action,value,handler,handlerClass,handlerRegistered} / {@code slot,invSlot,bound} /
 * {@code alphaTag,alphaRest,slide[3]}（一次性脉冲与入场位移的**声明**）/
 * {@code sem}（由注册的 {@link NodeFacts} 提供的语义：槽位路径、已装模块、语言键原文…）。</p>
 *
 * <h2>如实说明的三点边界</h2>
 * <ol>
 *   <li><b>不采样逐帧动画</b>：{@code ChasmGuiAnimations.entry/smooth/timeline} 都会推进内部时间戳，
 *       探针调一次就改变了画面。所以 dump 里是**声明值**（rect/opacity/delay/tag），
 *       不是"这一时刻被平滑到哪了"。要做视觉对比请在界面稳定后取帧（入场动画跑完）。</li>
 *   <li><b>lambda 的 handler 名只能到声明类</b>：JVM 不保留 lambda/方法引用的方法名，
 *       注册表里只有对象。{@code handler} = 去掉 {@code $$Lambda…} 之后的声明类
 *       （例 {@code com.example.chasm.tetra.TetraPort}），{@code handlerClass} 是原始类名；
 *       查不到注册（节点有动作但表里没有）→ {@code handler} 与 {@code handlerClass} 都是 {@code null}
 *       且 {@code handlerRegistered:false} —— 如实写 null，绝不编一个名字。</li>
 *   <li><b>不猜语义</b>：{@code sem}/{@code slots[].*_key} 里的每一项都由模组自己通过
 *       {@link #setNodeFacts}/{@link #setSlotFacts}/{@link #setLineFacts} 注册的 provider 提供；
 *       框架不替任何模组推断"这个槽位是什么"。</li>
 * </ol>
 */
public final class GuiProbe {

	/** dump 行的格式版本（diff 工具按它选解析器）。 */
	public static final String SCHEMA = "chasm.gui.probe/1";

	/** 文件路径属性 / 环境变量。 */
	public static final String PROP_FILE = "chasm.gui.probe.file";
	public static final String ENV_FILE = "CHASM_GUI_PROBE_FILE";
	/** 写入模式：{@code change}（默认，只写状态变化的那一帧）/ {@code every}（每次绘制都写）。 */
	public static final String PROP_MODE = "chasm.gui.probe.mode";
	public static final String ENV_MODE = "CHASM_GUI_PROBE_MODE";
	/** 单纯开关（不给 file 时用默认文件名）。 */
	public static final String PROP_FLAG = "chasm.gui.probe";
	public static final String ENV_FLAG = "CHASM_GUI_PROBE";

	/** 不给路径时的默认 dump 文件名（相对游戏工作目录）。 */
	public static final String DEFAULT_FILE = "chasm-gui-probe.jsonl";

	/** 探针是否开启（类加载时读一次；关闭时 {@link DeclClient} 里的调用点被 JIT 消除）。 */
	public static final boolean ENABLED;
	/** 是否"每次绘制都写"（false = 只在状态变化时写）。 */
	public static final boolean EVERY_DRAW;
	/** dump 文件（{@link #ENABLED} 为 false 时是 null）。 */
	public static final String FILE;

	/** 内存里最多保留多少行（防止长时间开启把堆吃满；落盘不受此限制）。 */
	private static final int MAX_KEPT_LINES = 8192;

	static {
		String file = config(PROP_FILE, ENV_FILE);
		String mode = config(PROP_MODE, ENV_MODE);
		String flag = config(PROP_FLAG, ENV_FLAG);
		boolean on = file != null || truthy(flag);
		if (on && file == null) {
			file = DEFAULT_FILE;
		}
		FILE = file;
		ENABLED = on;
		EVERY_DRAW = "every".equalsIgnoreCase(mode) || "all".equalsIgnoreCase(mode) || truthy(mode);
		if (ENABLED) {
			ChasmLogger.info("chasm", "界面探针开启：{}（模式 {}）", FILE, EVERY_DRAW ? "every 每次绘制" : "change 状态变化");
		}
	}

	private GuiProbe() {
	}

	// ==================================================================================
	// 钩子（DeclClient 在"节点列表建好之后、绘制之前"调用）
	// ==================================================================================

	private static final Recorder ACTIVE = new Recorder();
	private static volatile boolean writeFailed;

	/**
	 * **记录这一帧**（由 {@link DeclClient#render} 在画任何节点之前调用）。
	 *
	 * <p>关闭时立刻返回；开启时把一行 JSONL 追加到 {@link #FILE}。</p>
	 *
	 * @param left 面板原点 x（屏幕坐标），写进 {@code origin}
	 * @param top  面板原点 y
	 */
	public static void onDraw(ResourceLocation guiId, ChasmMenu menu, List<GuiNode> nodes, int left, int top) {
		if (!ENABLED) {
			return;
		}
		String line;
		try {
			line = ACTIVE.record(guiId, menu, nodes, left, top, EVERY_DRAW);
		} catch (RuntimeException | LinkageError e) {
			if (!writeFailed) {
				writeFailed = true;
				ChasmLogger.warn("chasm", "界面探针构建 dump 行失败（本次跳过，不再重复报）: {}", e.toString());
			}
			return;
		}
		if (line != null && FILE != null) {
			appendLine(FILE, line);
		}
	}

	private static void appendLine(String file, String line) {
		try {
			Files.writeString(Path.of(file), line + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
		} catch (Exception e) {
			if (!writeFailed) {
				writeFailed = true;
				ChasmLogger.warn("chasm", "界面探针写文件失败（{}）: {}", file, e.toString());
			}
		}
	}

	/** 探针是否开启（诊断/测试用）。 */
	public static boolean enabled() {
		return ENABLED;
	}

	/** 是否每次绘制都写（诊断/测试用）。 */
	public static boolean everyDraw() {
		return EVERY_DRAW;
	}

	/** dump 文件路径（未配置 → null）。 */
	public static String file() {
		return FILE;
	}

	/** 生产实例已写出的行数（诊断用）。 */
	public static int activeLineCount() {
		return ACTIVE.lineCount();
	}

	// ==================================================================================
	// 语义 provider（模组注册；框架不替谁猜"这个槽位是什么"）
	// ==================================================================================

	/** **界面级**语义：整行的 {@code sem} 对象（例：当前是第几页、选中的槽位路径）。 */
	@FunctionalInterface
	public interface LineFacts {
		void describe(ResourceLocation guiId, ChasmMenu menu, Map<String, String> out);
	}

	/** **槽位级**语义：{@code slots[i]} 上的附加字段（例：{@code slotPath}、{@code role}）。 */
	@FunctionalInterface
	public interface SlotFacts {
		void describe(ChasmMenu menu, int slotIndex, ChasmStorageSlot slot, ItemStack stack, Map<String, String> out);
	}

	/** **节点级**语义：{@code nodes[i].sem} 上的附加字段（例：语言键原文、槽位路径、已装模块）。 */
	@FunctionalInterface
	public interface NodeFacts {
		void describe(ResourceLocation guiId, ChasmMenu menu, GuiNode node, Map<String, String> out);
	}

	private static volatile LineFacts lineFacts;
	private static volatile SlotFacts slotFacts;
	private static volatile NodeFacts nodeFacts;

	public static void setLineFacts(LineFacts facts) {
		lineFacts = facts;
	}

	public static void setSlotFacts(SlotFacts facts) {
		slotFacts = facts;
	}

	public static void setNodeFacts(NodeFacts facts) {
		nodeFacts = facts;
	}

	// ==================================================================================
	// 可离线测试的记录器
	// ==================================================================================

	/**
	 * **记录器**：把"这一帧"变成一行 JSONL，并按界面 id 记住上一帧用于算变化。
	 *
	 * <p>生产环境用框架内的一份（{@link GuiProbe#ACTIVE}）；测试可以直接 new 一个自己的，
	 * 不碰文件、不碰客户端 —— 这就是本探针的**离线可测入口**。</p>
	 */
	public static final class Recorder {

		private final Map<ResourceLocation, String> lastBody = new HashMap<>();
		private final Map<ResourceLocation, Map<String, String>> lastState = new HashMap<>();
		private final Map<ResourceLocation, Map<String, String>> lastNodes = new HashMap<>();
		private final List<String> kept = new ArrayList<>();
		private long seq;

		/**
		 * 记录一帧；**没有变化且不是 every 模式 → 返回 null（不写）**。
		 *
		 * @param everyDraw true = 每次调用都出一行（"每刷新一次扫一遍"）；false = 只在与上一行不同时出
		 * @return JSONL 一行；本次不写 → null
		 */
		public synchronized String record(ResourceLocation guiId, ChasmMenu menu, List<GuiNode> nodes,
										  int left, int top, boolean everyDraw) {
			List<GuiNode> safe = nodes == null ? Collections.emptyList() : nodes;
			Map<String, String> state = stateOf(menu);
			Map<String, String> currentNodes = nodeSignatures(safe, menu);
			String body = body(guiId, menu, safe, left, top);
			boolean same = body.equals(lastBody.get(guiId));
			if (same && !everyDraw) {
				return null;
			}
			List<String> changed = changedKeys(lastState.get(guiId), state);
			String delta = deltaJson(lastNodes.get(guiId), currentNodes);
			lastBody.put(guiId, body);
			lastState.put(guiId, state);
			lastNodes.put(guiId, currentNodes);

			long s = ++seq;
			String line = "{\"schema\":\"" + SCHEMA + "\""
				+ ",\"seq\":" + s
				+ ",\"ts\":" + System.currentTimeMillis()
				+ ",\"mode\":\"" + (everyDraw ? "every" : "change") + "\""
				+ ",\"hash\":\"" + Integer.toHexString(body.hashCode()) + "\""
				+ ",\"stateChanged\":" + stringArray(changed)
				+ ",\"delta\":" + delta
				+ "," + body.substring(1);
			if (kept.size() < MAX_KEPT_LINES) {
				kept.add(line);
			}
			return line;
		}

		/** 记录一帧（{@link #record(ResourceLocation, ChasmMenu, List, int, int, boolean)} 的默认版：origin 0,0 / change 模式）。 */
		public synchronized String record(ResourceLocation guiId, ChasmMenu menu, List<GuiNode> nodes) {
			return record(guiId, menu, nodes, 0, 0, false);
		}

		/** 已经写出的行数（离线测试用它断言"dump 行数"）。 */
		public synchronized int lineCount() {
			return kept.size();
		}

		/** 已经写出的行（只保留最近 {@value GuiProbe#MAX_KEPT_LINES} 行）。 */
		public synchronized List<String> lines() {
			return List.copyOf(kept);
		}

		/** 忘掉某界面的"上一帧"（界面关闭时调用；下次打开的同状态会重新出一行）。 */
		public synchronized void forget(ResourceLocation guiId) {
			lastBody.remove(guiId);
			lastState.remove(guiId);
			lastNodes.remove(guiId);
		}

		/** 当前序号（已写行数）。 */
		public synchronized long lastSeq() {
			return seq;
		}
	}

	// ==================================================================================
	// 状态
	// ==================================================================================

	/** 全部 int 键与大值键的当前值（键序 = 声明序，dump 里因此是稳定的）。 */
	private static Map<String, String> stateOf(ChasmMenu menu) {
		Map<String, String> out = new LinkedHashMap<>();
		if (menu == null || menu.gui() == null) {
			return out;
		}
		for (ChasmGuiState.IntSlot slot : menu.gui().intSlots()) {
			out.put(slot.key(), Integer.toString(menu.data(slot.key())));
		}
		for (ChasmGuiState.ValueKey<?> key : menu.gui().stateKeys()) {
			Object value;
			try {
				value = menu.state(key.key());
			} catch (RuntimeException e) {
				value = "<读取失败:" + e.getClass().getSimpleName() + ">";
			}
			out.put(key.key(), truncate(String.valueOf(value), 400));
		}
		return out;
	}

	private static List<String> changedKeys(Map<String, String> previous, Map<String, String> current) {
		List<String> changed = new ArrayList<>();
		for (Map.Entry<String, String> entry : current.entrySet()) {
			if (previous == null || !entry.getValue().equals(previous.get(entry.getKey()))) {
				changed.add(entry.getKey());
			}
		}
		return changed;
	}

	/** 节点签名（用于 delta：同一 key 的几何/文字/颜色/动作是否变了）。 */
	private static Map<String, String> nodeSignatures(List<GuiNode> nodes, ChasmMenu menu) {
		Map<String, String> out = new LinkedHashMap<>();
		for (GuiNode node : nodes) {
			if (node == null) {
				continue;
			}
			GuiRect rect = node.rect(menu);
			out.put(node.key(), rect.x() + "," + rect.y() + "," + rect.width() + "," + rect.height()
				+ "|" + node.text() + "|" + node.spriteTint() + "|" + node.fillColor()
				+ "|" + node.action() + "#" + node.value() + "|" + node.isEnabled() + "|" + node.layer());
		}
		return out;
	}

	private static String deltaJson(Map<String, String> previous, Map<String, String> current) {
		if (previous == null) {
			return "{\"first\":true}";
		}
		List<String> added = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		for (String key : current.keySet()) {
			if (!previous.containsKey(key)) {
				added.add(key);
			} else if (!previous.get(key).equals(current.get(key))) {
				changed.add(key);
			}
		}
		for (String key : previous.keySet()) {
			if (!current.containsKey(key)) {
				removed.add(key);
			}
		}
		return "{\"added\":" + stringArray(added) + ",\"removed\":" + stringArray(removed)
			+ ",\"changed\":" + stringArray(changed) + "}";
	}

	// ==================================================================================
	// 一行 JSON
	// ==================================================================================

	/** 一行（不含 seq/ts/mode/hash/stateChanged/delta —— 那几项由 {@link Recorder} 拼在最前面）。 */
	private static String body(ResourceLocation guiId, ChasmMenu menu, List<GuiNode> nodes, int left, int top) {
		StringBuilder sb = new StringBuilder(1 << 14);
		sb.append("{");
		sb.append("\"gui\":").append(json(guiId == null ? null : guiId.toString()));
		sb.append(",\"title\":").append(json(menu == null || menu.gui() == null ? null : menu.gui().title().getString()));
		sb.append(",\"origin\":[").append(left).append(',').append(top).append(']');
		sb.append(",\"panel\":[")
			.append(menu == null || menu.gui() == null ? 0 : menu.gui().panelWidth()).append(',')
			.append(menu == null || menu.gui() == null ? 0 : menu.gui().panelHeight()).append(']');
		sb.append(",\"menu\":").append(menuJson(menu));
		sb.append(",\"sem\":").append(factsJson(lineFacts, guiId, menu));
		sb.append(",\"stateDecl\":").append(stateDeclJson(menu));
		sb.append(",\"state\":").append(stateJson(menu, true));
		sb.append(",\"stateValues\":").append(stateJson(menu, false));
		sb.append(",\"slots\":").append(slotsJson(menu));
		sb.append(",\"nodes\":").append(nodesJson(guiId, menu, nodes));
		sb.append(",\"interactive\":").append(interactiveJson(guiId, menu, nodes));
		sb.append(",\"nodesCount\":").append(nodes.size());
		sb.append('}');
		return sb.toString();
	}

	private static String menuJson(ChasmMenu menu) {
		if (menu == null) {
			return "null";
		}
		return "{\"syncId\":" + menu.containerId
			+ ",\"clientSide\":" + menu.isClientSide()
			+ ",\"bound\":" + menu.isBound()
			+ ",\"storageSlots\":" + (menu.gui() == null ? 0 : menu.gui().storageSlots().size())
			+ ",\"playerSlots\":36}";
	}

	private static String stateDeclJson(ChasmMenu menu) {
		if (menu == null || menu.gui() == null) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (ChasmGuiState.IntSlot slot : menu.gui().intSlots()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(json(slot.key())).append(":[").append(slot.defaultValue()).append(',')
				.append(slot.min()).append(',').append(slot.max()).append(']');
		}
		for (ChasmGuiState.ValueKey<?> key : menu.gui().stateKeys()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(json(key.key())).append(":\"value\"");
		}
		return sb.append('}').toString();
	}

	private static String stateJson(ChasmMenu menu, boolean ints) {
		if (menu == null || menu.gui() == null) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		if (ints) {
			for (ChasmGuiState.IntSlot slot : menu.gui().intSlots()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(json(slot.key())).append(':').append(menu.data(slot.key()));
			}
		} else {
			for (ChasmGuiState.ValueKey<?> key : menu.gui().stateKeys()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				Object value;
				try {
					value = menu.state(key.key());
				} catch (RuntimeException e) {
					value = "<读取失败>";
				}
				sb.append(json(key.key())).append(':').append(json(truncate(String.valueOf(value), 400)));
			}
		}
		return sb.append('}').toString();
	}

	/** 存储槽（几何 + 可见性 + 物品）：**服务端语义的落点**由 provider 补（slotPath 等）。 */
	private static String slotsJson(ChasmMenu menu) {
		if (menu == null || menu.gui() == null) {
			return "[]";
		}
		List<ChasmStorageSlot> declared = menu.gui().storageSlots();
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < declared.size(); i++) {
			ChasmStorageSlot slot = declared.get(i);
			if (i > 0) {
				sb.append(',');
			}
			ItemStack stack = menu.getItem(i);
			boolean active;
			try {
				active = slot.isActive(menu);
			} catch (RuntimeException e) {
				active = false;
			}
			sb.append("{\"i\":").append(i)
				.append(",\"containerIndex\":").append(slot.index())
				.append(",\"x\":").append(slot.pixelX(menu))
				.append(",\"y\":").append(slot.pixelY(menu))
				.append(",\"active\":").append(active)
				.append(",\"dynamic\":").append(slot.isDynamic())
				.append(",\"filtered\":").append(slot.filter() != null)
				.append(",\"empty\":").append(stack.isEmpty())
				.append(",\"item\":").append(json(stack.isEmpty() ? null : itemId(stack)))
				.append(",\"count\":").append(stack.getCount());
			String facts = factsJson(slotFacts, menu, i, slot, stack);
			if (!"{}".equals(facts)) {
				sb.append(",\"sem\":").append(facts);
			}
			sb.append('}');
		}
		return sb.append(']').toString();
	}

	private static String nodesJson(ResourceLocation guiId, ChasmMenu menu, List<GuiNode> nodes) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < nodes.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(nodeJson(guiId, menu, nodes.get(i), i));
		}
		return sb.append(']').toString();
	}

	/** 一个节点的 JSON（公开：离线测试直接拿它和真实 {@link GuiNode} 逐字段比对）。 */
	public static String nodeJson(ResourceLocation guiId, ChasmMenu menu, GuiNode node, int index) {
		if (node == null) {
			return "null";
		}
		GuiRect rect = node.rect(menu);
		StringBuilder sb = new StringBuilder(512);
		sb.append("{\"i\":").append(index);
		sb.append(",\"key\":").append(json(node.key()));
		sb.append(",\"type\":").append(json(primaryType(node)));
		sb.append(",\"draw\":").append(stringArray(drawPasses(node)));
		sb.append(",\"layer\":").append(json(node.layer().name()));
		sb.append(",\"rect\":[").append(rect.x()).append(',').append(rect.y()).append(',')
			.append(rect.width()).append(',').append(rect.height()).append(']');
		sb.append(",\"texture\":").append(json(node.texture() == null ? null : node.texture().toString()));
		sb.append(",\"uv\":[").append(node.u()).append(',').append(node.v()).append(',')
			.append(node.texWidth()).append(',').append(node.texHeight()).append(']');
		sb.append(",\"src\":[").append(node.srcWidth()).append(',').append(node.srcHeight()).append(']');
		sb.append(",\"scaled\":").append(node.isScaled());
		sb.append(",\"tint\":").append(hex(node.spriteTint()));
		sb.append(",\"fill\":").append(hex(node.fillColor()));
		sb.append(",\"text\":").append(json(node.text()));
		sb.append(",\"textScale\":").append(trim(node.textScale()));
		sb.append(",\"shadow\":").append(node.textShadow());
		sb.append(",\"color\":").append(hex(node.color()));
		sb.append(",\"hover\":").append(hex(node.hoverColor()));
		sb.append(",\"opacity\":").append(trim(node.opacity()));
		sb.append(",\"delay\":").append(trim(node.delay()));
		sb.append(",\"enabled\":").append(node.isEnabled());
		sb.append(",\"tooltip\":").append(json(node.tooltip()));
		sb.append(",\"action\":").append(json(node.action()));
		sb.append(",\"value\":").append(node.value());
		HandlerRef handler = handlerOf(guiId, node.action());
		sb.append(",\"handler\":").append(json(handler == null ? null : handler.name()));
		sb.append(",\"handlerClass\":").append(json(handler == null ? null : handler.clazz()));
		sb.append(",\"handlerRegistered\":").append(handler != null);
		sb.append(",\"slot\":").append(node.slotIndex());
		sb.append(",\"invSlot\":").append(node.inventoryIndex());
		sb.append(",\"bound\":").append(node.isBound());
		sb.append(",\"alphaTag\":").append(json(node.alphaTag()));
		sb.append(",\"alphaRest\":").append(trim(node.alphaRest()));
		sb.append(",\"slide\":[").append(node.slideX()).append(',').append(node.slideY())
			.append(',').append(node.slideMs()).append(']');
		String facts = factsJson(nodeFacts, guiId, menu, node);
		if (!"{}".equals(facts)) {
			sb.append(",\"sem\":").append(facts);
		}
		return sb.append('}').toString();
	}

	/** **可交互接口导出**：所有带动作的节点（动作 id + value + 绑定的服务端 handler）。 */
	private static String interactiveJson(ResourceLocation guiId, ChasmMenu menu, List<GuiNode> nodes) {
		StringBuilder sb = new StringBuilder("[");
		boolean first = true;
		for (int i = 0; i < nodes.size(); i++) {
			GuiNode node = nodes.get(i);
			if (node == null || node.action() == null) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			GuiRect rect = node.rect(menu);
			HandlerRef handler = handlerOf(guiId, node.action());
			sb.append("{\"i\":").append(i)
				.append(",\"node\":").append(json(node.key()))
				.append(",\"action\":").append(json(node.action()))
				.append(",\"value\":").append(node.value())
				.append(",\"enabled\":").append(node.isEnabled())
				.append(",\"rect\":[").append(rect.x()).append(',').append(rect.y()).append(',')
				.append(rect.width()).append(',').append(rect.height()).append(']')
				.append(",\"handler\":").append(json(handler == null ? null : handler.name()))
				.append(",\"handlerClass\":").append(json(handler == null ? null : handler.clazz()))
				.append(",\"handlerRegistered\":").append(handler != null)
				.append('}');
		}
		return sb.append(']').toString();
	}

	/** handler 的名字：注册表查到的对象 → 声明类名（lambda 去掉 {@code $$Lambda…}）；查不到 → null。 */
	private record HandlerRef(String name, String clazz) {
	}

	private static HandlerRef handlerOf(ResourceLocation guiId, String actionId) {
		if (guiId == null || actionId == null) {
			return null;
		}
		GuiDeclarations.ActionHandler handler;
		try {
			handler = GuiDeclarations.actionOf(guiId, actionId);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
		if (handler == null) {
			return null;
		}
		String clazz = handler.getClass().getName();
		// JDK 的隐藏类命名在不同版本里是 $$Lambda$123/0x… 或 $$Lambda/0x…（实测 JDK 25 是后者），
		// 所以只匹配前缀 "$$Lambda"，两种都能切到声明类。
		int lambda = clazz.indexOf("$$Lambda");
		String name = lambda > 0 ? clazz.substring(0, lambda) : clazz;
		return new HandlerRef(name, clazz);
	}

	// ------------------------------------------------------------ 节点分类

	private static String primaryType(GuiNode node) {
		if (node.slotIndex() >= 0) {
			return "slot";
		}
		if (node.inventoryIndex() >= 0) {
			return "inventorySlot";
		}
		if (node.texture() != null) {
			if (node.isScaled()) {
				return "spriteRegion";
			}
			return node.u() == 0 && node.v() == 0 && node.texWidth() == node.width()
				&& node.texHeight() == node.height() ? "image" : "sprite";
		}
		if (node.isFill()) {
			return "fill";
		}
		if (node.text() != null) {
			return "text";
		}
		return "rect";
	}

	/** 这一帧真的会画哪几遍（{@link DeclClient#render} 的三个 if）。 */
	private static List<String> drawPasses(GuiNode node) {
		List<String> passes = new ArrayList<>(3);
		if (node.isFill()) {
			passes.add("fill");
		}
		if (node.texture() != null) {
			passes.add("sprite");
		}
		if (node.text() != null) {
			passes.add("text");
		}
		if (passes.isEmpty()) {
			passes.add("none");   // 纯几何/纯命中节点：不画任何东西，但仍是列表一员
		}
		return passes;
	}

	// ------------------------------------------------------------ provider 调用

	private static String factsJson(LineFacts facts, ResourceLocation guiId, ChasmMenu menu) {
		Map<String, String> out = new LinkedHashMap<>();
		if (facts != null) {
			try {
				facts.describe(guiId, menu, out);
			} catch (RuntimeException | LinkageError e) {
				out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}
		return stringMapJson(out);
	}

	private static String factsJson(SlotFacts facts, ChasmMenu menu, int index, ChasmStorageSlot slot, ItemStack stack) {
		Map<String, String> out = new LinkedHashMap<>();
		if (facts != null) {
			try {
				facts.describe(menu, index, slot, stack, out);
			} catch (RuntimeException | LinkageError e) {
				out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}
		return stringMapJson(out);
	}

	private static String factsJson(NodeFacts facts, ResourceLocation guiId, ChasmMenu menu, GuiNode node) {
		Map<String, String> out = new LinkedHashMap<>();
		if (facts != null) {
			try {
				facts.describe(guiId, menu, node, out);
			} catch (RuntimeException | LinkageError e) {
				out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}
		return stringMapJson(out);
	}

	// ------------------------------------------------------------ JSON 原子

	static String stringMapJson(Map<String, String> map) {
		if (map == null || map.isEmpty()) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> entry : map.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(json(entry.getKey())).append(':').append(json(entry.getValue()));
		}
		return sb.append('}').toString();
	}

	static String stringArray(List<String> values) {
		if (values == null || values.isEmpty()) {
			return "[]";
		}
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(json(values.get(i)));
		}
		return sb.append(']').toString();
	}

	/** JSON 字符串字面量（null → {@code null}）。 */
	static String json(String value) {
		if (value == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder(value.length() + 2);
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

	/** ARGB → {@code "#AARRGGBB"}（0 也用同一个格式，便于 diff 时一眼看出"没设色"）。 */
	static String hex(int argb) {
		return String.format("\"#%08X\"", argb);
	}

	/** 去掉浮点噪声（0.30000000000000004 → 0.3）。 */
	static String trim(double value) {
		if (value == Math.floor(value) && !Double.isInfinite(value) && Math.abs(value) < 1.0E7) {
			return Long.toString((long) value);
		}
		return Double.toString(Math.round(value * 1000.0) / 1000.0);
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max) + "…";
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	// ------------------------------------------------------------ 配置

	private static String config(String property, String env) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			value = System.getenv(env);
		}
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static boolean truthy(String value) {
		if (value == null) {
			return false;
		}
		return "on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)
			|| "yes".equalsIgnoreCase(value) || "1".equals(value);
	}
}
