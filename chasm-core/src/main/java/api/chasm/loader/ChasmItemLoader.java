package api.chasm.loader;

import api.chasm.Chasm;
import api.chasm.context.ChasmContextRegistry;
import api.chasm.item.ChasmItemSupport;
import api.chasm.item.ChasmTypes;
import api.chasm.item.ItemBuilder;
import api.chasm.item.ItemType;
import api.chasm.log.ChasmLogger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Locale;
import java.util.Map;

/**
 * JSON 软编码物品加载器。
 *
 * <p>读取 classpath 下 {@code data/<ns>/chasm/items/*.json}，用 {@link ItemBuilder}
 * 构建物品，并在运行时（onInitialize、注册表冻结前）真实注册进
 * {@link BuiltInRegistries#ITEM}，同时暴露到 {@link api.chasm.context.ModContext}
 * 物品控制台；在
 * DataGen 阶段（系统属性 {@code fabric-api.datagen} 存在）只"收集声明"（暴露到
 * ModContext），<b>不</b>注册进注册表（DataGen 期注册表已冻结）。</p>
 *
 * <p>幂等：可重复调用，同名已注册的物品会被跳过并告警。</p>
 */
public final class ChasmItemLoader {

	/** 全局单例。 */
	public static final ChasmItemLoader INSTANCE = new ChasmItemLoader();

	private ChasmItemLoader() {
	}

	/**
	 * 加载并处理当前 classpath 下所有 {@code <ns>/chasm/items/*.json}。
	 *
	 * <p>幂等：重复加载时，已经注册的同名物品会被跳过并 warn。单文件解析失败
	 * 不影响其余文件。</p>
	 */
	public void loadAll() {
		for (ChasmResources.ChasmResource r : ChasmResources.find("items")) {
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
			JsonObject obj = gson(r);
			if (obj == null) {
				return;
			}
			String idStr = obj.get("id").getAsString();
			ResourceLocation id = ResourceLocation.parse(idStr);

			// 1) 重复注册防护：注册表已存在该 id 的独立物品则跳过
			if (BuiltInRegistries.ITEM.get(id) != null) {
				ChasmLogger.warn(id.getNamespace(), "JSON 物品 {} 已存在，跳过重复注册", id);
				return;
			}

			ItemBuilder builder = Chasm.item();

			// 2) 类型
			ItemType appliedType = applyType(builder, obj, id);

			// 3) 原版 Tier
			applyTier(builder, obj, id);

			// 4) 数值字段
			if (obj.has("attack_damage") && obj.get("attack_damage").isJsonPrimitive()) {
				builder.attackDamage(obj.get("attack_damage").getAsFloat());
			}
			if (obj.has("attack_speed") && obj.get("attack_speed").isJsonPrimitive()) {
				builder.attackSpeed(obj.get("attack_speed").getAsFloat());
			}
			if (obj.has("max_stack") && obj.get("max_stack").isJsonPrimitive()) {
				builder.maxStackSize(obj.get("max_stack").getAsInt());
			}
			if (obj.has("durability") && obj.get("durability").isJsonPrimitive()) {
				builder.durability(obj.get("durability").getAsInt());
			}
			if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
				builder.name(obj.get("name").getAsString());
			}
			if (obj.has("texture") && obj.get("texture").isJsonPrimitive()) {
				builder.texture(obj.get("texture").getAsString());
			}

			// 5) 特质
			applyTraits(builder, obj, id);

			// 6) 自带附魔
			applyEnchantments(builder, obj, id);

			// 7) 数据组件（按已注册的 DATA_COMPONENT_TYPE 用 Gson→Codec 解码）
			applyComponents(builder, obj, id);

			// 8) 构建物品实例（ChasmItem 系列皆实现 ChasmItemSupport，可安全 cast）
			Item item = builder.register();

			// 9) 运行时注册 / DataGen 仅收集声明
			boolean datagen = System.getProperty("fabric-api.datagen") != null;
			if (datagen) {
				exposeIfSupport(id, item);
			} else {
				Registry.register(BuiltInRegistries.ITEM, id, item);
				exposeIfSupport(id, item);
			}

			ChasmLogger.info(id.getNamespace(), "从 JSON 注册物品 {} (type={})",
				id, appliedType != null ? appliedType.id() : "none");
		} catch (Exception e) {
			ChasmLogger.error("chasm", "加载 JSON 物品 {} 失败: {}", debugName, String.valueOf(e));
		}
	}

	// —— 解析辅助 ——

	/** 读取资源流并解析为 JsonObject；解析失败返回 null（记日志）。 */
	private static JsonObject gson(ChasmResources.ChasmResource r) throws Exception {
		String json = new String(r.stream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		JsonElement el = JsonParser.parseString(json);
		if (el.isJsonObject()) {
			return el.getAsJsonObject();
		}
		ChasmLogger.warn(r.namespace(), "JSON 物品资源 {} 顶层不是对象，跳过", r.name());
		return null;
	}

	/** 解析物品类型（可选键 "type"）；取不到则按普通物品处理。返回实际绑定的类型（可为 null）。 */
	private static ItemType applyType(ItemBuilder builder, JsonObject obj, ResourceLocation id) {
		JsonElement typeEl = obj.get("type");
		if (typeEl == null || !typeEl.isJsonPrimitive()) {
			return null;
		}
		String tc = typeEl.getAsString();
		try {
			ResourceLocation trl = ResourceLocation.parse(tc);
			ItemType type = ChasmTypes.INSTANCE.get(trl).orElse(null);
			if (type == null) {
				// 内置快捷类型兜底
				if (trl.equals(ResourceLocation.fromNamespaceAndPath("chasm", "sword"))) {
					type = ChasmTypes.SWORD;
				} else if (trl.equals(ResourceLocation.fromNamespaceAndPath("chasm", "none"))) {
					type = ChasmTypes.NONE;
				} else if (trl.equals(ResourceLocation.fromNamespaceAndPath("chasm", "pickaxe"))) {
					type = ChasmTypes.PICKAXE;
				} else if (trl.equals(ResourceLocation.fromNamespaceAndPath("chasm", "axe"))) {
					type = ChasmTypes.AXE;
				} else {
					ChasmLogger.warn(id.getNamespace(), "JSON 物品 {} 类型 {} 未注册，按普通物品处理", id, trl);
				}
			}
			if (type != null) {
				builder.type(type);
				return type;
			}
		} catch (Exception e) {
			ChasmLogger.warn(id.getNamespace(), "解析 JSON 物品 {} 类型 {} 失败: {}", id, tc, String.valueOf(e));
		}
		return null;
	}

	/** 解析原版 Tier（可选键 "tier"，如 "minecraft:diamond"）。 */
	private static void applyTier(ItemBuilder builder, JsonObject obj, ResourceLocation id) {
		JsonElement tierEl = obj.get("tier");
		if (tierEl == null || !tierEl.isJsonPrimitive()) {
			return;
		}
		String t = tierEl.getAsString();
		try {
			String path = ResourceLocation.parse(t).getPath();
			Tier tier = Tiers.valueOf(path.toUpperCase(Locale.ROOT));
			builder.tier(tier);
		} catch (Exception e) {
			ChasmLogger.warn(id.getNamespace(), "JSON 物品 {} Tier {} 解析失败: {}", id, t, String.valueOf(e));
		}
	}

	/** 解析特质绑定（可选对象 traits：use/attack/inventory）。 */
	private static void applyTraits(ItemBuilder builder, JsonObject obj, ResourceLocation id) {
		JsonElement traitsEl = obj.get("traits");
		if (traitsEl == null || !traitsEl.isJsonObject()) {
			return;
		}
		JsonObject tr = traitsEl.getAsJsonObject();
		try {
			if (tr.has("use") && tr.get("use").isJsonPrimitive()) {
				builder.useTrait(ResourceLocation.parse(tr.get("use").getAsString()));
			}
			if (tr.has("attack") && tr.get("attack").isJsonPrimitive()) {
				builder.attackTrait(ResourceLocation.parse(tr.get("attack").getAsString()));
			}
			if (tr.has("inventory") && tr.get("inventory").isJsonPrimitive()) {
				builder.inventoryTrait(ResourceLocation.parse(tr.get("inventory").getAsString()));
			}
		} catch (Exception e) {
			ChasmLogger.warn(id.getNamespace(), "JSON 物品 {} 特质解析失败: {}", id, String.valueOf(e));
		}
	}

	/** 解析自带附魔（可选数组 enchant：[{id, level}]）。 */
	private static void applyEnchantments(ItemBuilder builder, JsonObject obj, ResourceLocation id) {
		JsonElement encEl = obj.get("enchant");
		if (encEl == null || !encEl.isJsonArray()) {
			return;
		}
		for (JsonElement e : encEl.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject jo = e.getAsJsonObject();
			if (!jo.has("id") || !jo.get("id").isJsonPrimitive()) {
				continue;
			}
			try {
				String enchId = jo.get("id").getAsString();
				int level = jo.has("level") && jo.get("level").isJsonPrimitive() ? jo.get("level").getAsInt() : 1;
				ResourceKey<Enchantment> key =
					ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.parse(enchId));
				builder.enchant(key, level);
			} catch (Exception ex) {
				ChasmLogger.warn(id.getNamespace(), "JSON 物品 {} 附魔解析失败: {}", id, String.valueOf(ex));
			}
		}
	}

	/** 解析数据组件（可选对象 components：组件 id → 值，按已注册类型用 Codec 解码）。 */
	private static void applyComponents(ItemBuilder builder, JsonObject obj, ResourceLocation id) {
		JsonElement compEl = obj.get("components");
		if (compEl == null || !compEl.isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> en : compEl.getAsJsonObject().entrySet()) {
			String cid = en.getKey();
			JsonElement val = en.getValue();
			try {
				ResourceLocation rl = ResourceLocation.parse(cid);
				@SuppressWarnings("rawtypes")
				DataComponentType ct = BuiltInRegistries.DATA_COMPONENT_TYPE.get(rl);
				if (ct == null) {
					ChasmLogger.warn(id.getNamespace(), "JSON 物品 {} 组件 {} 未注册，跳过", id, rl);
					continue;
				}
				// 用组件的 Codec 从 Gson 元素解码成目标类型
				Object decoded = ct.codec().parse(JsonOps.INSTANCE, val).result().orElse(null);
				if (decoded == null) {
					ChasmLogger.warn(id.getNamespace(), "JSON 物品 {} 组件 {} 值解码失败，跳过", id, rl);
					continue;
				}
				addComponent(builder, ct, decoded);
			} catch (Exception ex) {
				ChasmLogger.warn(id.getNamespace(), "JSON 物品 {} 组件处理失败: {}", id, String.valueOf(ex));
			}
		}
	}

	/** 追加一个数据组件默认值（经原始类型以规避 ItemBuilder 泛型签名）。 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void addComponent(ItemBuilder builder, DataComponentType ct, Object value) {
		builder.component((DataComponentType) ct, (Object) value);
	}

	/** 若物品实现 {@link ChasmItemSupport} 则暴露到对应模组上下文物品控制台。 */
	private static void exposeIfSupport(ResourceLocation id, Item item) {
		if (item instanceof ChasmItemSupport support) {
			ChasmContextRegistry.get(id.getNamespace()).exposeItem(id.getPath(), support);
		}
	}
}