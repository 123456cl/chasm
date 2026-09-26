package api.chasm.effect;

import api.chasm.loader.ChasmResources;
import api.chasm.log.ChasmLogger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **自定义药水效果注册入口**（框架能力）—— 让端口能注册自己的 {@link MobEffect}，
 * 既支持代码声明（{@link #register(String, String)} + {@link Builder}），
 * 也支持**数据驱动**（{@link #fromJson(String, JsonObject)} / {@link #loadFromClasspath()}）。
 *
 * <h2>为什么必须补这一层</h2>
 * <p>真实 Tetra 有 17 个自定义效果，全部经 Forge 的 {@code DeferredRegister} 注册：
 * {@code TetraRegistries.java:115}（{@code DeferredRegister<MobEffect>}）+ {@code :385-401}（17 行注册），
 * 注册名就是各效果类里的 {@code identifier} 常量（{@code BleedingPotionEffect.java:29} 等）。
 * 移植端口此前**没有等价入口**，于是 {@code bleeding/severing/stun/earthbind} 只能停在"算法写好但没处挂"。</p>
 *
 * <h2>三种注册姿势（都为 O(1)）</h2>
 * <pre>
 * // 1) 代码声明（推荐，能挂 tick 行为）
 * ChasmMobEffects.register("mymod", "bleeding")
 *     .category(MobEffectCategory.HARMFUL)
 *     .color(0x880000)
 *     .interval((duration, amplifier) -&gt; duration % 10 == 0)
 *     .onTick((entity, amplifier) -&gt; { entity.hurt(src, amplifier); return true; })
 *     .register();
 *
 * // 2) 数据驱动（一个 JsonObject = 一个效果；属性/类别/颜色/瞬时全在参数里）
 * ChasmMobEffects.fromJson("mymod", json);
 *
 * // 3) 数据驱动（classpath 目录 data/&lt;ns&gt;/chasm/mob_effects/*.json 批量）
 * ChasmMobEffects.loadFromClasspath();
 * </pre>
 *
 * <p><b>时机</b>：原版 {@code BuiltInRegistries.MOB_EFFECT} 在启动期冻结，必须在
 * {@code onInitialize()} 里注册（与物品/方块同一约束）。数据驱动走的是**模组自带资源**
 * （classpath / jar 内），不是数据包 —— 数据包在冻结之后才加载，那时无法再注册注册表内容。</p>
 *
 * <p><b>零 NPE</b>：id 非法、类别未知、属性 id 不存在、JSON 结构不对 —— 一律记日志并跳过，
 * 绝不抛异常、绝不返回 null（{@link #get} 返回 {@code Optional}、{@link #holderOf} 返回 null 有文档）。</p>
 */
public final class ChasmMobEffects {

	/** 注册后的效果：id → 实例（注册表冻结后保持不变）。 */
	private static final Map<ResourceLocation, MobEffect> REGISTERED = new ConcurrentHashMap<>();
	/** 注册后的 Holder 缓存（应用效果时用，避免每次查注册表）。 */
	private static final Map<ResourceLocation, Holder<MobEffect>> HOLDERS = new ConcurrentHashMap<>();
	/** 是否真的进了**原版注册表**（false = 注册表已冻结，退回内存登记 + 直接 Holder，效果仍可用）。 */
	private static final Map<ResourceLocation, Boolean> IN_GAME_REGISTRY = new ConcurrentHashMap<>();

	private ChasmMobEffects() {
	}

	// ------------------------------------------------------------------ 代码声明

	/** 开始声明一个效果（{@code <modId>:<name>}）。 */
	public static Builder register(String modId, String name) {
		return new Builder(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/** 开始声明一个效果（ResourceLocation 版本）。 */
	public static Builder register(ResourceLocation id) {
		return new Builder(id);
	}

	/** 效果声明器：链式配置后 {@link #register()} 落注册表。 */
	public static final class Builder {

		private final ResourceLocation id;
		private MobEffectCategory category = MobEffectCategory.NEUTRAL;
		private int color = 0xFFFFFF;
		private boolean instant;
		private ChasmMobEffect.IntervalFn interval;
		private ChasmMobEffect.TickFn tick;

		private Builder(ResourceLocation id) {
			this.id = id;
		}

		/** 类别（BENEFICIAL / HARMFUL / NEUTRAL）。 */
		public Builder category(MobEffectCategory category) {
			if (category != null) {
				this.category = category;
			}
			return this;
		}

		/** 粒子/文本颜色（0xRRGGBB）。 */
		public Builder color(int rgb) {
			this.color = rgb & 0xFFFFFF;
			return this;
		}

		/** 瞬时效果（与 {@code InstantenousMobEffect} 同语义）。 */
		public Builder instant() {
			this.instant = true;
			return this;
		}

		/** 挂一条属性修饰符（真实 {@code MobEffect#addAttributeModifier}，等级会按 amplifier 线性放大）。 */
		public Builder attribute(Holder<Attribute> attribute, ResourceLocation modifierId,
								 double amount, AttributeModifier.Operation operation) {
			if (attribute != null && modifierId != null && operation != null) {
				this.attributes.add(new AttributeSpec(attribute, modifierId, amount, operation));
			}
			return this;
		}

		/** 挂一条属性修饰符（属性 id 字符串版；查不到属性则记日志跳过）。 */
		public Builder attribute(String attributeId, String modifierId,
								 double amount, AttributeModifier.Operation operation) {
			ResourceLocation attrKey = parse(attributeId);
			Attribute attribute = attrKey == null ? null : BuiltInRegistries.ATTRIBUTE.get(attrKey);
			if (attribute == null) {
				ChasmLogger.warn(id.getNamespace(), "效果 {} 的属性 {} 未注册，已跳过该修饰符", id, attributeId);
				return this;
			}
			return attribute(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute), parse(modifierId), amount, operation);
		}

		/** tick 间隔判定（真实 {@code isDurationEffectTick} 的等价物）。 */
		public Builder interval(ChasmMobEffect.IntervalFn interval) {
			this.interval = interval;
			return this;
		}

		/**
		 * 每 N tick 生效一次（最常用的写法；N ≤ 0 视为不 tick）。
		 * 真实用例：Bleeding {@code duration % 10 == 0}、Stun {@code duration % 4 == 0}。
		 */
		public Builder everyTicks(int period) {
			if (period <= 0) {
				this.interval = null;
				return this;
			}
			this.interval = (duration, amplifier) -> duration % period == 0;
			return this;
		}

		/** tick 行为。 */
		public Builder onTick(ChasmMobEffect.TickFn tick) {
			this.tick = tick;
			return this;
		}

		private final java.util.List<AttributeSpec> attributes = new java.util.ArrayList<>(2);

		/** 落到原版效果注册表并返回实例（重复 id 以最后一次为准；同时刷新 Holder 缓存）。 */
		public MobEffect register() {
			ChasmMobEffect effect = new ChasmMobEffect(category, color, instant, interval, tick);
			for (AttributeSpec spec : attributes) {
				try {
					effect.addAttributeModifier(spec.attribute(), spec.modifierId(), spec.amount(), spec.operation());
				} catch (Throwable t) {
					ChasmLogger.warn(id.getNamespace(), "效果 {} 的属性修饰符挂载失败（已跳过）: {}", id, String.valueOf(t));
				}
			}
			return ChasmMobEffects.put(id, effect);
		}
	}

	/** 一条属性修饰符声明。 */
	private record AttributeSpec(Holder<Attribute> attribute, ResourceLocation modifierId, double amount,
								 AttributeModifier.Operation operation) {
	}

	// ------------------------------------------------------------------ 数据驱动

	/**
	 * **数据驱动注册**（id + 参数）：一个 JSON 对象 = 一个效果。
	 *
	 * <pre>
	 * {
	 *   "id": "bleeding",
	 *   "category": "harmful",          // beneficial | harmful | neutral
	 *   "color": "0x880000",            // 或十进制整数 8912896
	 *   "instant": false,
	 *   "attributes": [
	 *     { "attribute": "minecraft:generic.movement_speed",
	 *       "modifier": "mymod:bleeding/movement_speed",
	 *       "amount": -0.3, "operation": "add_multiplied_total" }
	 *   ]
	 * }
	 * </pre>
	 *
	 * @return 注册成功返回效果实例；结构不合法返回 null（并记日志）
	 */
	public static MobEffect fromJson(String modId, JsonObject json) {
		if (json == null) {
			return null;
		}
		String name = optString(json, "id");
		if (name == null || name.isBlank()) {
			ChasmLogger.warn(modId, "药水效果 JSON 缺 id，已跳过");
			return null;
		}
		ResourceLocation id = name.contains(":") ? parse(name) : ResourceLocation.fromNamespaceAndPath(modId, name);
		if (id == null) {
			ChasmLogger.warn(modId, "药水效果 id 非法（{}），已跳过", name);
			return null;
		}
		Builder builder = register(id);
		builder.category(categoryByName(optString(json, "category")));
		String color = optString(json, "color");
		if (color != null) {
			builder.color(parseColor(color));
		}
		if (json.has("instant") && json.get("instant").isJsonPrimitive() && json.get("instant").getAsJsonPrimitive().isBoolean()
			&& json.get("instant").getAsBoolean()) {
			builder.instant();
		}
		if (json.has("interval") && json.get("interval").isJsonPrimitive()) {
			try {
				builder.everyTicks(json.get("interval").getAsInt());
			} catch (RuntimeException e) {
				ChasmLogger.warn(modId, "药水效果 {} 的 interval 非法，已忽略", id);
			}
		}
		JsonElement attrs = json.get("attributes");
		if (attrs != null && attrs.isJsonArray()) {
			JsonArray array = attrs.getAsJsonArray();
			for (JsonElement element : array) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject entry = element.getAsJsonObject();
				String attribute = optString(entry, "attribute");
				String modifier = optString(entry, "modifier");
				if (attribute == null) {
					continue;
				}
				AttributeModifier.Operation operation = operationByName(optString(entry, "operation"));
				double amount = 0.0D;
				try {
					amount = entry.has("amount") && entry.get("amount").isJsonPrimitive()
						? entry.get("amount").getAsDouble() : 0.0D;
				} catch (RuntimeException ex) {
					// amount 写了字符串等非法值：记一次告警按 0 处理，绝不因一条坏数据丢掉整个效果
					ChasmLogger.warn(modId, "药水效果 {} 的属性 {} 的 amount 非法，按 0 处理", id, attribute);
				}
				ResourceLocation modifierId = modifier == null || modifier.isBlank()
					? ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "/" + sanitize(attribute))
					: parse(modifier);
				builder.attribute(attribute, modifierId == null ? null : modifierId.toString(), amount, operation);
			}
		}
		return builder.register();
	}

	/**
	 * **批量数据驱动**：扫描 classpath（开发环境 {@code build/resources/main}、打包后模组 jar）里的
	 * {@code data/<ns>/chasm/mob_effects/*.json} 并逐个 {@link #fromJson}。
	 *
	 * <p>复用框架既有的资源扫描器 {@link ChasmResources#find(String)}，因此目录与 jar 两种形态都支持。</p>
	 *
	 * @return 成功注册的效果数量
	 */
	public static int loadFromClasspath() {
		int count = 0;
		List<ChasmResources.ChasmResource> found;
		try {
			found = ChasmResources.find("mob_effects");
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "扫描 mob_effects 资源失败（已隔离）", t);
			return 0;
		}
		for (ChasmResources.ChasmResource resource : found) {
			if (resource == null || resource.stream() == null) {
				continue;
			}
			try (InputStreamReader reader = new InputStreamReader(resource.stream(), StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (parsed == null || !parsed.isJsonObject()) {
					ChasmLogger.warn(resource.namespace(), "药水效果 {} 的根不是 JSON 对象，已跳过", resource.name());
					continue;
				}
				if (fromJson(resource.namespace(), parsed.getAsJsonObject()) != null) {
					count++;
				}
			} catch (Throwable t) {
				ChasmLogger.error(resource.namespace(), "药水效果 {} 解析失败（已跳过该文件）", resource.name(), t);
			}
		}
		if (count > 0) {
			ChasmLogger.call("chasm", "ChasmMobEffects", "loadFromClasspath", "数据驱动注册了 {} 个药水效果", count);
		}
		return count;
	}

	// ------------------------------------------------------------------ 查询

	/** 框架登记的效果（未注册 → 空）。注意：这是**声明表**，权威仍是原版注册表。 */
	public static Optional<MobEffect> get(ResourceLocation id) {
		return id == null ? Optional.empty() : Optional.ofNullable(REGISTERED.get(id));
	}

	/** 框架登记的效果（未注册 → 空）。 */
	public static Optional<MobEffect> get(String modId, String name) {
		return get(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/** 效果 Holder（未注册 → null；调用方需判空；用于 {@code MobEffectInstance}）。 */
	public static Holder<MobEffect> holderOf(ResourceLocation id) {
		return id == null ? null : HOLDERS.get(id);
	}

	/** 效果 Holder（未注册 → null）。 */
	public static Holder<MobEffect> holderOf(String modId, String name) {
		return holderOf(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/**
	 * 该效果是否真的注册进了原版 {@code BuiltInRegistries.MOB_EFFECT}。
	 *
	 * <p>{@code false} 只会在"注册表已冻结"的场合出现（例如单测里的 {@code Bootstrap.bootStrap()}，
	 * 实测抛 {@code IllegalStateException: Registry is already frozen}）——此时效果仍经
	 * {@link Holder#direct(Object)} 可用，只是不在原版注册表里（无法被命令/药水引用）。</p>
	 */
	public static boolean isInGameRegistry(ResourceLocation id) {
		return id != null && Boolean.TRUE.equals(IN_GAME_REGISTRY.get(id));
	}

	/** 已登记的 id 快照（只读、稳定顺序 = 注册顺序不保证）。 */
	public static List<ResourceLocation> ids() {
		return List.copyOf(REGISTERED.keySet());
	}

	/** 已登记的效果数量。 */
	public static int size() {
		return REGISTERED.size();
	}

	/** 清空登记表（**仅供单测**；不动原版注册表，原版注册表本就不可逆）。 */
	static void clearForTests() {
		REGISTERED.clear();
		HOLDERS.clear();
		IN_GAME_REGISTRY.clear();
	}

	// ------------------------------------------------------------------ 内部

	/** 落注册表 + 记 Holder（幂等：同 id 覆盖，行为 = 最后一次声明）。 */
	private static MobEffect put(ResourceLocation id, MobEffect effect) {
		try {
			MobEffect previous = REGISTERED.get(id);
			if (previous == null) {
				// 原版注册表：同一 id 二次注册会抛异常，所以先查后注册（幂等）
				MobEffect existing = BuiltInRegistries.MOB_EFFECT.get(id);
				if (existing != null) {
					REGISTERED.put(id, existing);
					HOLDERS.put(id, BuiltInRegistries.MOB_EFFECT.wrapAsHolder(existing));
					IN_GAME_REGISTRY.put(id, Boolean.TRUE);
					return existing;
				}
				MobEffect registered = net.minecraft.core.Registry.register(BuiltInRegistries.MOB_EFFECT, id, effect);
				REGISTERED.put(id, registered);
				HOLDERS.put(id, BuiltInRegistries.MOB_EFFECT.wrapAsHolder(registered));
				IN_GAME_REGISTRY.put(id, Boolean.TRUE);
				ChasmLogger.call(id.getNamespace(), "ChasmMobEffects", "register", "注册药水效果 {}", id);
				return registered;
			}
			REGISTERED.put(id, effect);
			HOLDERS.put(id, BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect));
			return effect;
		} catch (Throwable t) {
			// 注册表已冻结（例如单测的 Bootstrap 环境，实测抛 IllegalStateException: Registry is already frozen）：
			// 退回内存登记 + **直接 Holder** —— 效果照样能被 MobEffectInstance 应用，绝不抛给调用方
			ChasmLogger.warn(id.getNamespace(), "注册药水效果 {} 未进原版注册表（已退回内存登记 + 直接 Holder）: {}",
				id, String.valueOf(t));
			REGISTERED.put(id, effect);
			HOLDERS.put(id, Holder.direct(effect));
			IN_GAME_REGISTRY.put(id, Boolean.FALSE);
			return effect;
		}
	}

	private static String optString(JsonObject json, String key) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		try {
			return element.getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static ResourceLocation parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return raw.contains(":") ? ResourceLocation.parse(raw) : ResourceLocation.withDefaultNamespace(raw);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 数据里的类别名 → 原版枚举（未知/缺失一律 NEUTRAL）。公开给数据驱动方与测试用。 */
	public static MobEffectCategory categoryByName(String raw) {
		if (raw == null) {
			return MobEffectCategory.NEUTRAL;
		}
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "beneficial", "good" -> MobEffectCategory.BENEFICIAL;
			case "harmful", "bad" -> MobEffectCategory.HARMFUL;
			default -> MobEffectCategory.NEUTRAL;
		};
	}

	/**
	 * 数据里的运算名 → 原版枚举（未知/缺失一律 {@code ADD_VALUE}）。
	 *
	 * <p>1.21.1 的枚举名（反汇编确认）：{@code ADD_VALUE / ADD_MULTIPLIED_BASE / ADD_MULTIPLIED_TOTAL}；
	 * 1.20 的 {@code ADDITION / MULTIPLY_BASE / MULTIPLY_TOTAL} 在 1.21 改名，语义一一对应，
	 * 所以两套名字都认（老数据/老文档不用改）。</p>
	 */
	public static AttributeModifier.Operation operationByName(String raw) {
		if (raw == null) {
			return AttributeModifier.Operation.ADD_VALUE;
		}
		return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
			case "multiply_total", "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
			case "multiply_base", "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
			default -> AttributeModifier.Operation.ADD_VALUE;
		};
	}

	/** 颜色支持 {@code "0xRRGGBB"} / {@code "#RRGGBB"} / 十进制整数。 */
	private static int parseColor(String raw) {
		try {
			String trimmed = raw.trim();
			if (trimmed.startsWith("#")) {
				return (int) (Long.parseLong(trimmed.substring(1), 16) & 0xFFFFFFL);
			}
			if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
				return (int) (Long.parseLong(trimmed.substring(2), 16) & 0xFFFFFFL);
			}
			return (int) (Long.parseLong(trimmed) & 0xFFFFFFL);
		} catch (RuntimeException e) {
			ChasmLogger.warn("chasm", "颜色 {} 解析失败，用白色代替", raw);
			return 0xFFFFFF;
		}
	}

	private static String sanitize(String raw) {
		StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '/' || c == '.' || c == '-' ? c : '_');
		}
		return sb.toString();
	}

}
