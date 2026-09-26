package api.chasm.damage;

import api.chasm.log.ChasmLogger;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 伤害类型全局注册表（单例，可查询、可聚合、跨模组访问）。
 *
 * <p>核心价值：让"伤害类型"成为框架的一等公民。任何模组经
 * {@link #register(String, String, DamageTypeKind)} 声明后即进入本表，
 * 其他模组 / 整合包可 {@link #get(ResourceLocation)} 精确查询，或
 * {@link #queryByTag(String)} 按聚合标签统一归类（如统一处理所有 "magic" 伤害）。</p>
 *
 * <p>内置模板（开箱即用，可直接引用）：{@link #PHYSICAL}、{@link #MAGIC}。
 * 原版类型不可经本表覆盖；本表仅做内存投影与声明式扩展。</p>
 */
public final class ChasmDamageTypes {

	/** 全局单例。 */
	public static final ChasmDamageTypes INSTANCE = new ChasmDamageTypes();

	/** 内置模板：物理伤害（受护甲减免）。 */
	public static final DamageTypeInfo PHYSICAL = INSTANCE
		.register("chasm", "physical", DamageTypeKind.PLAYER_ATTACK)
		.tag("physical")
		.build();

	/** 内置模板：魔法伤害（无视护甲，受魔抗影响）。 */
	public static final DamageTypeInfo MAGIC = INSTANCE
		.register("chasm", "magic", DamageTypeKind.MAGIC)
		.tag("magic")
		.build();

	private final Map<ResourceLocation, DamageTypeInfo> registry = new ConcurrentHashMap<>();

	private ChasmDamageTypes() {
	}

	/** 开始声明一个自定义伤害类型（返回 builder，链式配置后 {@code .build()} 注册）。 */
	public ChasmDamageTypeBuilder register(String modId, String name, DamageTypeKind kind) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		return newRegister(id, kind);
	}

	/** 开始声明一个自定义伤害类型（ResourceLocation 版本）。 */
	public ChasmDamageTypeBuilder register(ResourceLocation id, DamageTypeKind kind) {
		return newRegister(id, kind);
	}

	private ChasmDamageTypeBuilder newRegister(ResourceLocation id, DamageTypeKind kind) {
		BiConsumer<DamageTypeInfo, ResourceLocation> log = (info, res) -> ChasmLogger.info(
			res.getNamespace(), "注册伤害类型 {} (kind={}, message={}, bypassArmor={}, isFire={}, tags={})",
			res, info.kind(), info.messageId(), info.bypassesArmor(), info.isFire(), info.tags());
		return new ChasmDamageTypeBuilder(id, kind).onBuild(log);
	}

	void put(DamageTypeInfo info) {
		registry.put(info.id(), info);
	}

	/** 按 id 精确查询（返回 {@code Optional}，未注册为空）。 */
	public Optional<DamageTypeInfo> get(ResourceLocation id) {
		return Optional.ofNullable(registry.get(id));
	}

	/** 按 id 精确查询（void 安全包装，未注册不抛异常）。 */
	public DamageTypeInfo getOrThrow(ResourceLocation id) {
		DamageTypeInfo info = registry.get(id);
		if (info == null) {
			throw new IllegalArgumentException("未注册的伤害类型: " + id);
		}
		return info;
	}

	/** 按模组命名空间 + 名称查询。 */
	public Optional<DamageTypeInfo> get(String modId, String name) {
		return get(ResourceLocation.fromNamespaceAndPath(modId, name));
	}

	/** 当前注册的伤害类型总数。 */
	public int size() {
		return registry.size();
	}

	/** 所有伤害类型（不可直接改，只读聚合视图）。 */
	public java.util.Collection<DamageTypeInfo> all() {
		return java.util.Collections.unmodifiableCollection(registry.values());
	}
}