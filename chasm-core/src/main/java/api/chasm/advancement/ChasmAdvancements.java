package api.chasm.advancement;

import api.chasm.log.ChasmLogger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.ItemSubPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **进度系统注册门面**（框架能力）—— 数据包自定义触发器 / 物品子谓词的注册与查询。
 *
 * <h2>1.21.1 的真实注册点（javap 取证）</h2>
 * <pre>
 * net.minecraft.core.registries.BuiltInRegistries:
 *   public static final Registry&lt;CriterionTrigger&lt;?&gt;&gt; TRIGGER_TYPES;
 *   public static final Registry&lt;ItemSubPredicate$Type&lt;?&gt;&gt; ITEM_SUB_PREDICATE_TYPE;
 * net.minecraft.advancements.Criterion 静态块（javap -c）:
 *   MAP_CODEC = ExtraCodecs.dispatchOptionalValue("trigger", "conditions",
 *       CriteriaTriggers.CODEC, Criterion::trigger, CriterionTrigger::createCriterion)
 *   CODEC = MAP_CODEC.codec()
 * net.minecraft.advancements.CriteriaTriggers 静态块（javap -c）:
 *   CODEC = BuiltInRegistries.TRIGGER_TYPES.byNameCodec()
 * </pre>
 * <p>所以成就 JSON 的 {@code {"trigger": "<id>", "conditions": {...}}} 是靠
 * <b>TRIGGER_TYPES 注册表按名反查</b>的：id 不在表里 → 整条 criterion 解析失败 →
 * 该成就整条不加载。这就是"注册了名字"是硬前提的字节码级依据。</p>
 *
 * <h2>为什么需要"数据驱动注册"的口子</h2>
 * <p>1.20 的触发器靠 {@code createInstance(JsonObject, ContextAwarePredicate, DeserializationContext)}；
 * 1.21.1 改成 {@code CriterionTrigger.codec()}（DFU）。老模组/参考实现里的条件解析**全是
 * JsonObject 风格**（例如 {@code se.mickelus.tetra.advancements.GenericTrigger.TriggerDeserializer}），
 * 逐条改写成 {@code RecordCodecBuilder} 既啰嗦又容易走样。所以本类提供
 * {@link #jsonCodec}/{@link #criterionCodec}：<b>把 JsonObject 反序列化原样接进 1.21.1 的 codec 体系</b>，
 * 同时保留可编码能力（数据生成/往返测试都要用）。</p>
 */
public final class ChasmAdvancements {

	/** JSON → 条件对象（不含 player 字段的通用形状，物品子谓词等用）。 */
	@FunctionalInterface
	public interface JsonDecoder<T> {
		T decode(JsonObject json);
	}

	/** 条件对象 → JSON（反向编码；数据生成与往返测试需要）。 */
	@FunctionalInterface
	public interface JsonEncoder<T> {
		JsonElement encode(T value);
	}

	/** 触发器条件 JSON → 条件实例（player 字段已由框架解析好）。 */
	@FunctionalInterface
	public interface TriggerDecoder<T> {
		T decode(JsonObject conditions, Optional<ContextAwarePredicate> player);
	}

	/** {@code player} 条件的标准解析器（真实 1.21.1 的判据：{@code EntityPredicate.ADVANCEMENT_CODEC}）。 */
	private static final Codec<Optional<ContextAwarePredicate>> PLAYER_CODEC =
		EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").codec();

	/** 注册表冻结后才尝试注册的 id（正常运行应为空；非空 = 注册写晚了）。 */
	private static final Set<ResourceLocation> LATE_REGISTRATIONS = ConcurrentHashMap.newKeySet();

	/**
	 * **解码期"当前 registry-aware ops"**（2026-09-23 修复）。
	 *
	 * <p>事故：{@code itemPredicate}/{@code parse} 之前固定用 {@link JsonOps#INSTANCE} 解析
	 * {@link ItemPredicate#CODEC}。1.21.1 的 {@code ItemPredicate.items} 是 {@code HolderSet}，
	 * 必须走 {@link RegistryOps} 才能查 ITEM 注册表 —— 用 JsonOps 会直接报
	 * {@code Can't access registry ResourceKey[minecraft:root / minecraft:item]}，
	 * 使引用它的整条 criterion（进而整个成就）不加载；{@code tetra:craft_module} /
	 * {@code tetra:craft_improvement} 的 {@code before}/{@code after}、{@code tetra:block_use} 的
	 * {@code item} 都是这条路径。</p>
	 *
	 * <p>修法：{@link Codec#PASSTHROUGH} 把"原始输入 + 原始 ops"包成 {@link Dynamic}，
	 * 所以 loader 传进来的 {@link RegistryOps} 在 {@link #decodeJson} 里是拿得到的。
	 * 解码期间把它放进本 ThreadLocal，{@link #activeOps()} 优先返回它；没有时才用
	 * {@link #fallbackOps()}（离线单测 / 其它调用方）。用 ThreadLocal 是因为数据包加载本身单线程，
	 * 但 codec 可能被并发调用，不能落一个全局可变字段。</p>
	 */
	private static final ThreadLocal<DynamicOps<JsonElement>> ACTIVE_OPS = new ThreadLocal<>();

	/** 兜底 ops（惰性构造：避免类初始化早于 Bootstrap / 注册表就绪）。 */
	private static volatile DynamicOps<JsonElement> fallbackOps;

	/**
	 * **兜底 registry-aware ops**：只包 {@code BuiltInRegistries}。
	 *
	 * <p>游戏内数据包加载走的是"透传 loader 的 RegistryOps"（见 {@link #decodeJson}），
	 * 它能看见数据驱动注册表（附魔等），<b>不需要</b>这个兜底。兜底只服务两类调用方：
	 * 离线单测、以及直接调 {@link #itemPredicate}/{@link #parse} 而外层没有 ops 上下文的代码。
	 * {@code BuiltInRegistries.REGISTRY} 里的 ITEM/BLOCK 足够成就数据用；数据驱动注册表不在其中。</p>
	 */
	private static DynamicOps<JsonElement> fallbackOps() {
		DynamicOps<JsonElement> local = fallbackOps;
		if (local == null) {
			local = RegistryOps.create(JsonOps.INSTANCE,
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
			fallbackOps = local;
		}
		return local;
	}

	/** 当前可用的 ops：优先 loader / 调用方透传的 RegistryOps，其次 {@link #fallbackOps()}。 */
	private static DynamicOps<JsonElement> activeOps() {
		DynamicOps<JsonElement> active = ACTIVE_OPS.get();
		return active != null ? active : fallbackOps();
	}

	/**
	 * 从 {@link Codec#PASSTHROUGH} 解出的 {@link Dynamic} 里取回 loader 的 registry-aware ops。
	 *
	 * <p>只有"值本身是 {@link JsonElement} 且 ops 是 {@link RegistryOps}"时才透传
	 * （数据包 JSON 加载正是这种）；NBT 等其它载体回落 {@link #fallbackOps()}。</p>
	 */
	@SuppressWarnings("unchecked")
	private static DynamicOps<JsonElement> registryAwareOps(Dynamic<?> dynamic) {
		if (dynamic.getValue() instanceof JsonElement && dynamic.getOps() instanceof RegistryOps<?> registryOps) {
			return (DynamicOps<JsonElement>) registryOps;
		}
		return fallbackOps();
	}

	private ChasmAdvancements() {
	}

	// ------------------------------------------------------------------ 注册

	/**
	 * 注册一个**数据驱动触发器**：给一个 id + 一个条件 codec，返回可触发的触发器。
	 *
	 * <pre>{@code
	 * public static final ChasmCriterionTrigger<MyInstance> MY_TRIGGER =
	 *         ChasmAdvancements.registerTrigger("mymod", "my_event", MyInstance.CODEC);
	 * }</pre>
	 *
	 * @param modId 模组命名空间
	 * @param name  触发器名（不含命名空间），完整 id = {@code modId:name}
	 * @param codec 条件实例 codec（可用 {@link #criterionCodec} 从 JSON 反序列化搭出来）
	 */
	public static <T extends SimpleCriterionTrigger.SimpleInstance> ChasmCriterionTrigger<T> registerTrigger(
		String modId, String name, Codec<T> codec) {
		return registerTrigger(modId, name, new ChasmCriterionTrigger<>(
			ResourceLocation.fromNamespaceAndPath(modId, name), codec));
	}

	/**
	 * 注册一个**已实现好的**触发器实例（迁移既有 {@code CriterionTrigger} 用）。
	 *
	 * <p>幂等：同 id 已存在时直接复用既有实例（DataGen 会重复扫描同一批静态字段）。</p>
	 *
	 * @return 注册表里那个实例（可能是既有的）
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <C extends CriterionTrigger<?>> C registerTrigger(String modId, String name, C trigger) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		CriterionTrigger<?> existing = BuiltInRegistries.TRIGGER_TYPES.get(id);
		if (existing != null) {
			return (C) existing;
		}
		try {
			Registry.register((Registry) BuiltInRegistries.TRIGGER_TYPES, id, trigger);
			ChasmLogger.call(modId, "ChasmAdvancements", "registerTrigger", "注册进度触发器 {}", id);
		} catch (IllegalStateException frozen) {
			LATE_REGISTRATIONS.add(id);
			ChasmLogger.error(modId,
				"进度触发器 {} 在注册表冻结后才注册（未真正登记）—— 引用它的成就整条加载失败。"
					+ "修复：在 onInitialize 里提前注册。", id);
		}
		return trigger;
	}

	/** 注册一个物品子谓词类型（{@code ItemPredicate.predicates} 里的 {@code "type"} 键）。 */
	public static <T extends ItemSubPredicate> ItemSubPredicate.Type<T> registerItemSubPredicate(
		String modId, String name, Codec<T> codec) {
		ItemSubPredicate.Type<T> type = new ItemSubPredicate.Type<>(codec);
		return (ItemSubPredicate.Type<T>) registerItemSubPredicate(modId, name, (ItemSubPredicate.Type<?>) type);
	}

	/** 注册一个已构造好的物品子谓词类型（幂等）。 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static ItemSubPredicate.Type<?> registerItemSubPredicate(String modId, String name, ItemSubPredicate.Type<?> type) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		ItemSubPredicate.Type<?> existing = BuiltInRegistries.ITEM_SUB_PREDICATE_TYPE.get(id);
		if (existing != null) {
			return existing;
		}
		try {
			Registry.register((Registry) BuiltInRegistries.ITEM_SUB_PREDICATE_TYPE, id, type);
			ChasmLogger.call(modId, "ChasmAdvancements", "registerItemSubPredicate", "注册物品子谓词 {}", id);
		} catch (IllegalStateException frozen) {
			LATE_REGISTRATIONS.add(id);
			ChasmLogger.error(modId,
				"物品子谓词 {} 在注册表冻结后才注册（未真正登记）—— 引用它的成就整条加载失败。"
					+ "修复：在 onInitialize 里提前注册。", id);
		}
		return type;
	}

	// ------------------------------------------------------------------ 查询

	/** 按 id 取触发器（未注册 → 空）。 */
	public static Optional<CriterionTrigger<?>> trigger(ResourceLocation id) {
		return id == null ? Optional.empty() : Optional.ofNullable(BuiltInRegistries.TRIGGER_TYPES.get(id));
	}

	/** 按 id 取物品子谓词类型（未注册 → 空）。 */
	public static Optional<ItemSubPredicate.Type<?>> itemSubPredicate(ResourceLocation id) {
		return id == null ? Optional.empty() : Optional.ofNullable(BuiltInRegistries.ITEM_SUB_PREDICATE_TYPE.get(id));
	}

	/** 全部已注册触发器 id（只读快照，按注册顺序）。 */
	public static Set<ResourceLocation> triggerIds() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(BuiltInRegistries.TRIGGER_TYPES.keySet()));
	}

	/** 全部已注册物品子谓词 id（只读快照）。 */
	public static Set<ResourceLocation> itemSubPredicateIds() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(BuiltInRegistries.ITEM_SUB_PREDICATE_TYPE.keySet()));
	}

	/** 冻结后才注册成功的 id（空 = 时序正确）。 */
	public static Set<ResourceLocation> lateRegistrations() {
		return Set.copyOf(LATE_REGISTRATIONS);
	}

	// ------------------------------------------------------------------ codec 桥（JsonObject ⇄ DFU Codec）

	/**
	 * **JSON 条件 codec**：把 JsonObject 风格的条件解析接进 1.21.1 的 codec 体系。
	 *
	 * <p>解码时把传入的 {@code Dynamic} 统一转成 JSON 再交给 {@code decoder}；
	 * 编码时用 {@code encoder} 产出 JSON 再包回 {@code Dynamic}。</p>
	 */
	public static <T> Codec<T> jsonCodec(JsonDecoder<T> decoder, JsonEncoder<T> encoder) {
		if (decoder == null || encoder == null) {
			throw new IllegalArgumentException("jsonCodec 需要解码器与编码器");
		}
		return Codec.PASSTHROUGH.flatXmap(
			dynamic -> decodeJson(decoder, dynamic),
			value -> encodeJson(encoder, value));
	}

	/**
	 * **进度条件 codec**：在 {@link #jsonCodec} 基础上自动解析标准 {@code player} 字段。
	 *
	 * <p>{@code player} 字段的语义逐字来自原版：{@code SimpleCriterionTrigger.trigger} 会用
	 * {@code EntityPredicate.createContext(player, player)} 匹配它（javap 字节码实证），
	 * 所以条件实例只要把 {@code player()} 返回出去，玩家条件就自动生效。</p>
	 */
	public static <T extends SimpleCriterionTrigger.SimpleInstance> Codec<T> criterionCodec(
		TriggerDecoder<T> decoder, JsonEncoder<T> encoder) {
		return jsonCodec(json -> decoder.decode(json, playerOf(json)), encoder);
	}

	/** 解析 {@code conditions.player}（缺失 → 空；格式错误 → JsonSyntaxException）。 */
	public static Optional<ContextAwarePredicate> playerOf(JsonObject conditions) {
		if (conditions == null || !conditions.has("player")) {
			return Optional.empty();
		}
		DataResult<Optional<ContextAwarePredicate>> parsed = PLAYER_CODEC.parse(activeOps(), conditions);
		return parsed.getOrThrow(message -> new JsonSyntaxException("player 条件解析失败: " + message));
	}

	private static <T> DataResult<T> decodeJson(JsonDecoder<T> decoder, Dynamic<?> dynamic) {
		JsonElement element;
		try {
			element = dynamic.convert(JsonOps.INSTANCE).getValue();
		} catch (RuntimeException e) {
			return DataResult.error(() -> "条件无法转换为 JSON: " + e);
		}
		if (element == null || !element.isJsonObject()) {
			return DataResult.error(() -> "条件必须是 JSON 对象，实际为 " + element);
		}
		// 只在"转成 JSON 值"这一步用 JsonOps；loader 原来的 RegistryOps 透传给解码器用
		//（itemPredicate / playerOf 需要注册表）。见 ACTIVE_OPS 的说明。
		DynamicOps<JsonElement> previous = ACTIVE_OPS.get();
		ACTIVE_OPS.set(registryAwareOps(dynamic));
		try {
			return DataResult.success(decoder.decode(element.getAsJsonObject()));
		} catch (RuntimeException e) {
			return DataResult.error(() -> "条件解析失败: " + e);
		} finally {
			if (previous == null) {
				ACTIVE_OPS.remove();
			} else {
				ACTIVE_OPS.set(previous);
			}
		}
	}

	private static <T> DataResult<Dynamic<?>> encodeJson(JsonEncoder<T> encoder, T value) {
		try {
			return DataResult.success(new Dynamic<>(JsonOps.INSTANCE, encoder.encode(value)));
		} catch (RuntimeException e) {
			return DataResult.error(() -> "条件编码失败: " + e);
		}
	}

	// ------------------------------------------------------------------ JSON 读取小工具

	/**
	 * 直接按 codec 解析一段 JSON（失败抛 JsonSyntaxException，带上下文）。
	 *
	 * <p>用 {@link #activeOps()} 而不是 {@link JsonOps#INSTANCE}：需要注册表的 codec
	 * （ItemPredicate / EntityPredicate / BlockPredicate…）在数据包加载期依赖透传的
	 * RegistryOps，离线/无上下文时回落 BuiltInRegistries 兜底。</p>
	 */
	public static <T> T parse(Codec<T> codec, JsonElement json, String what) {
		return codec.parse(activeOps(), json)
			.getOrThrow(message -> new JsonSyntaxException(what + " 解析失败: " + message));
	}

	/**
	 * 解析一段 {@code items}/{@code components}/{@code predicates} 形状的物品谓词。
	 *
	 * <p>{@code items} 是 HolderSet，必须用 registry-aware ops（见 {@link #activeOps()}）。</p>
	 */
	public static ItemPredicate itemPredicate(JsonElement json) {
		return parse(ItemPredicate.CODEC, json, "物品谓词");
	}

	/** 用子谓词造一个"只看这个子谓词"的物品谓词（例如 {@code tetra:modular_item}）。 */
	public static <T extends ItemSubPredicate> ItemPredicate itemPredicate(ItemSubPredicate.Type<T> type, T value) {
		return ItemPredicate.Builder.item().withSubPredicate(type, value).build();
	}

	/** 取一个可选字段（不存在 → 空）。 */
	public static Optional<JsonElement> field(JsonObject json, String key) {
		return json != null && json.has(key) ? Optional.of(json.get(key)) : Optional.empty();
	}

	/** 取一个可选字符串字段。 */
	public static Optional<String> string(JsonObject json, String key) {
		return field(json, key).filter(element -> !element.isJsonNull()).map(JsonElement::getAsString);
	}

	/** 取一个可选整数字段。 */
	public static Optional<Integer> integer(JsonObject json, String key) {
		return field(json, key).filter(element -> !element.isJsonNull()).map(JsonElement::getAsInt);
	}
}
