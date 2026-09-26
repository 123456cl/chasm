package api.chasm.loot;

import api.chasm.log.ChasmLogger;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **战利品注册门面**（框架能力）—— 数据包自定义战利品条件 / 函数的注册与查询。
 *
 * <h2>1.21.1 的真实注册点（javap 取证）</h2>
 * <pre>
 * net.minecraft.core.registries.BuiltInRegistries:
 *   public static final Registry&lt;LootItemConditionType&gt; LOOT_CONDITION_TYPE;
 *   public static final Registry&lt;LootItemFunctionType&lt;?&gt;&gt; LOOT_FUNCTION_TYPE;
 * net.minecraft.world.level.storage.loot.predicates.LootItemConditionType (record):
 *   public LootItemConditionType(com.mojang.serialization.MapCodec&lt;? extends LootItemCondition&gt;)
 * net.minecraft.world.level.storage.loot.functions.LootItemFunctionType (record):
 *   public LootItemFunctionType(com.mojang.serialization.MapCodec&lt;T&gt;)
 * net.minecraft.world.level.storage.loot.predicates.LootItemCondition:
 *   TYPED_CODEC / DIRECT_CODEC / CODEC（DIRECT_CODEC 按 LOOT_CONDITION_TYPE 名字分派）
 * </pre>
 * <p>战利品表 JSON 里的 {@code {"condition": "<id>", ...}} / {@code {"function": "<id>", ...}}
 * 就是按这两张表的名字反查的；id 不在表里 → 整张战利品表解析失败。</p>
 *
 * <p><b>1.20 → 1.21.1 的差别</b>：1.20 是 {@code Serializer<T>}（Gson 的
 * {@code JsonObject#deserialize}），1.21.1 全是 DFU {@link MapCodec}；
 * {@code LootItemConditionalFunction} 的公共字段（{@code conditions}）由
 * {@code commonFields(RecordCodecBuilder.Instance)} 提供（javap 实证）。</p>
 */
public final class ChasmLoot {

	/** 冻结后才尝试注册的 id（正常运行应为空）。 */
	private static final Set<ResourceLocation> LATE_REGISTRATIONS = ConcurrentHashMap.newKeySet();

	private ChasmLoot() {
	}

	/** 注册一个战利品条件类型。 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static LootItemConditionType registerCondition(String modId, String name,
		MapCodec<? extends LootItemCondition> codec) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		LootItemConditionType existing = BuiltInRegistries.LOOT_CONDITION_TYPE.get(id);
		if (existing != null) {
			return existing;
		}
		LootItemConditionType type = new LootItemConditionType(codec);
		try {
			Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, id, type);
			ChasmLogger.call(modId, "ChasmLoot", "registerCondition", "注册战利品条件 {}", id);
		} catch (IllegalStateException frozen) {
			LATE_REGISTRATIONS.add(id);
			ChasmLogger.error(modId,
				"战利品条件 {} 在注册表冻结后才注册（未真正登记）—— 引用它的战利品表整张加载失败。"
					+ "修复：在 onInitialize 里提前注册。", id);
		}
		return type;
	}

	/** 注册一个战利品函数类型。 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T extends LootItemFunction> LootItemFunctionType<T> registerFunction(String modId, String name,
		MapCodec<T> codec) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		LootItemFunctionType<?> existing = BuiltInRegistries.LOOT_FUNCTION_TYPE.get(id);
		if (existing != null) {
			return (LootItemFunctionType<T>) existing;
		}
		LootItemFunctionType<T> type = new LootItemFunctionType<>(codec);
		try {
			Registry.register((Registry) BuiltInRegistries.LOOT_FUNCTION_TYPE, id, type);
			ChasmLogger.call(modId, "ChasmLoot", "registerFunction", "注册战利品函数 {}", id);
		} catch (IllegalStateException frozen) {
			LATE_REGISTRATIONS.add(id);
			ChasmLogger.error(modId,
				"战利品函数 {} 在注册表冻结后才注册（未真正登记）—— 引用它的战利品表整张加载失败。"
					+ "修复：在 onInitialize 里提前注册。", id);
		}
		return type;
	}

	/** 按 id 取战利品条件类型（未注册 → 空）。 */
	public static Optional<LootItemConditionType> condition(ResourceLocation id) {
		return id == null ? Optional.empty() : Optional.ofNullable(BuiltInRegistries.LOOT_CONDITION_TYPE.get(id));
	}

	/** 按 id 取战利品函数类型（未注册 → 空）。 */
	public static Optional<LootItemFunctionType<?>> function(ResourceLocation id) {
		return id == null ? Optional.empty() : Optional.ofNullable(BuiltInRegistries.LOOT_FUNCTION_TYPE.get(id));
	}

	/** 全部已注册战利品条件 id（只读快照）。 */
	public static Set<ResourceLocation> conditionIds() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(BuiltInRegistries.LOOT_CONDITION_TYPE.keySet()));
	}

	/** 全部已注册战利品函数 id（只读快照）。 */
	public static Set<ResourceLocation> functionIds() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(BuiltInRegistries.LOOT_FUNCTION_TYPE.keySet()));
	}

	/** 冻结后才注册成功的 id（空 = 时序正确）。 */
	public static Set<ResourceLocation> lateRegistrations() {
		return Set.copyOf(LATE_REGISTRATIONS);
	}
}
