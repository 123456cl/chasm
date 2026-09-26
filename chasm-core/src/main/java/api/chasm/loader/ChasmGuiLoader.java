package api.chasm.loader;

import api.chasm.Chasm;
import api.chasm.gui.ChasmGuiBuilder;
import api.chasm.gui.ChasmGuiRegistry;
import api.chasm.log.ChasmLogger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

/**
 * JSON 软编码 GUI 加载器。
 *
 * <p>读取 classpath 下 {@code data/<ns>/chasm/gui/*.json}，用
 * {@link ChasmGuiBuilder} 构建并注册声明式界面。</p>
 *
 * <p><b>DataGen 期跳过</b>：{@link ChasmGuiBuilder#register()} 会创建
 * {@link net.minecraft.world.inventory.MenuType} 并注册进
 * {@link net.minecraft.core.registries.BuiltInRegistries#MENU}（该注册表在
 * DataGen 期已冻结、注册会抛异常），因此 DataGen 阶段本加载器不处理任何 GUI。
 * <b>运行时去重</b>：同 id 的界面只能注册一次（MenuType 只能注册一次），
 * loadSingle 开始先用 {@link ChasmGuiRegistry} 判重。</p>
 */
public final class ChasmGuiLoader {

	/** 全局单例。 */
	public static final ChasmGuiLoader INSTANCE = new ChasmGuiLoader();

	private ChasmGuiLoader() {
	}

	/**
	 * 加载并处理当前 classpath 下所有 {@code <ns>/chasm/gui/*.json}。
	 *
	 * <p>DataGen 期直接返回（不重建 MenuType）。运行时对每个已发现资源调用
	 * {@link #loadSingle}；单文件失败不影响其余文件。</p>
	 */
	public void loadAll() {
		if (System.getProperty("fabric-api.datagen") != null) {
			return;
		}
		for (ChasmResources.ChasmResource r : ChasmResources.find("gui")) {
			loadSingle(r);
		}
	}

	/**
	 * 单文件加载（供幂等 / 单测）。
	 *
	 * <p>整体由 try/catch 包裹：解析或注册失败仅记 error 日志，不抛出中断
	 * 其它文件的加载。</p>
	 *
	 * @param r 已发现的 JSON 资源
	 */
	public void loadSingle(ChasmResources.ChasmResource r) {
		String debugName = r.name();
		try {
			JsonObject obj = parse(r);
			if (obj == null) {
				return;
			}
			if (!obj.has("id") || !obj.get("id").isJsonPrimitive()) {
				ChasmLogger.error(r.namespace(), "JSON GUI {} 缺少 id，跳过", debugName);
				return;
			}
			ResourceLocation id = ResourceLocation.parse(obj.get("id").getAsString());
			String modId = id.getNamespace();
			String name = id.getPath();
			if (modId.isEmpty() || name.isEmpty()) {
				ChasmLogger.error("chasm", "JSON GUI {} id 无效: {}", debugName, id);
				return;
			}

			// 1) 判重：MenuType 只能注册一次
			ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(modId, name);
			if (ChasmGuiRegistry.get(rl) != null) {
				ChasmLogger.warn(modId, "JSON GUI {} 已存在，跳过重复注册", rl);
				return;
			}

			ChasmGuiBuilder b = Chasm.gui(modId, name);

			// 2) 标题 / 尺寸
			if (obj.has("title") && obj.get("title").isJsonPrimitive()) {
				b.title(obj.get("title").getAsString());
			}
			int cols = obj.has("cols") && obj.get("cols").isJsonPrimitive() ? obj.get("cols").getAsInt() : 9;
			int rows = obj.has("rows") && obj.get("rows").isJsonPrimitive() ? obj.get("rows").getAsInt() : 1;
			b.size(cols, rows);

			// 3) 背景（可选字符串 ResourceLocation）
			if (obj.has("background") && obj.get("background").isJsonPrimitive()) {
				b.background(ResourceLocation.parse(obj.get("background").getAsString()));
			}

			// 4) 存储槽
			applySlots(b, obj);

			// 5) 按钮
			applyButtons(b, obj);

			// 6) 注册（创建并注册 MenuType、写入 ChasmGuiRegistry）
			b.register();
			ChasmLogger.info(modId, "从 JSON 注册 GUI {}", rl);
		} catch (Exception e) {
			ChasmLogger.error("chasm", "加载 JSON GUI {} 失败: {}", debugName, String.valueOf(e));
		}
	}

	// —— 解析辅助 ——

	/** 读取资源流并解析为 JsonObject；解析失败返回 null（记日志）。 */
	private static JsonObject parse(ChasmResources.ChasmResource r) throws Exception {
		String json = new String(r.stream().readAllBytes(), StandardCharsets.UTF_8);
		JsonElement el = JsonParser.parseString(json);
		if (el.isJsonObject()) {
			return el.getAsJsonObject();
		}
		ChasmLogger.warn(r.namespace(), "JSON GUI 资源 {} 顶层不是对象，跳过", r.name());
		return null;
	}

	/** 解析 slots 数组：每项 {col,row,width,height} → 存储槽区域。 */
	private static void applySlots(ChasmGuiBuilder b, JsonObject obj) {
		JsonElement slotsEl = obj.get("slots");
		if (slotsEl == null || !slotsEl.isJsonArray()) {
			return;
		}
		for (JsonElement e : slotsEl.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject s = e.getAsJsonObject();
			int col = intOr(s, "col", 0);
			int row = intOr(s, "row", 0);
			int w = intOr(s, "width", 1);
			int h = intOr(s, "height", 1);
			b.slot(col, row, w, h);
		}
	}

	/** 解析 buttons 数组：每项 {label, col, row, action, cooldown?} → 按钮。 */
	private static void applyButtons(ChasmGuiBuilder b, JsonObject obj) {
		JsonElement buttonsEl = obj.get("buttons");
		if (buttonsEl == null || !buttonsEl.isJsonArray()) {
			return;
		}
		for (JsonElement e : buttonsEl.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject bo = e.getAsJsonObject();
			int col = intOr(bo, "col", 0);
			int row = intOr(bo, "row", 0);
			boolean actionDriven = bo.has("action") && bo.get("action").isJsonPrimitive();
			boolean labelDriven = bo.has("label") && bo.get("label").isJsonPrimitive();
			// 既无 action 也无 label 的无效按钮：直接跳过，不留下任何冷却状态污染后续按钮
			if (!actionDriven && !labelDriven) {
				continue;
			}
			// 冷却（可选，ticks）：仅在实际创建按钮前设置，确保只作用于本按钮
			int cooldown = intOr(bo, "cooldown", 0);
			b.buttonCooldown(cooldown);
			// 动作驱动按钮（label 缺省用 action 的 path）
			if (actionDriven) {
				String action = bo.get("action").getAsString();
				String label = labelDriven
					? bo.get("label").getAsString()
					: ResourceLocation.parse(action).getPath();
				b.buttonAction(ResourceLocation.parse(action), label, col, row);
			} else {
				// 仅文字（无动作）：空实现按钮
				String label = bo.get("label").getAsString();
				b.button(label, col, row, (player, click) -> {
				});
			}
		}
	}

	/** 便捷读取可选整数成员（缺省返回默认值）。 */
	private static int intOr(JsonObject o, String key, int def) {
		if (o.has(key) && o.get(key).isJsonPrimitive()) {
			return o.get(key).getAsInt();
		}
		return def;
	}
}