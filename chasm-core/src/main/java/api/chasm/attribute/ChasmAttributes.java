package api.chasm.attribute;

import api.chasm.log.ChasmLogger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 属性全局注册表（单例）：登记自定义属性 + 内建常用属性的查询视图。
 *
 * <p>自定义属性须真正注册进 {@link Registries#ATTRIBUTE} 才能被 {@code getAttributeValue}
 * 读取；本类在 {@code registerRanged} 时用 Fabric 的注册 API 同步写入原版属性注册表，
 * 并把元数据（default/min/max）记入自身以供跨模组查询与修饰符反推。</p>
 *
 * <p>内建查询：{@link #ATTACK_DAMAGE}、{@link #ATTACK_SPEED} 等常用属性由本类静态持有，
 * 其 {@link #attribute()} 直接指向原版 {@code Attributes} 常量，defaultValue 取原版默认值。</p>
 */
public final class ChasmAttributes {

	/** 内建：攻击伤害（默认 1.0…注意：1.21 真实基础值为 {@code Attributes.ATTACK_DAMAGE.value().getDefaultValue()}=2.0，声明 total=10 时修饰符 = 10-2 = +8）。 */
	public static final AttributeInfo ATTACK_DAMAGE = builtin(
		ResourceLocation.withDefaultNamespace("attack_damage"),
		net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.value(),
		(float) net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.value().getDefaultValue());
	/** 内建：攻击速度（默认 4.0，声明 total=0.5 时修饰符 = 0.5 - 4 = -3.5）。 */
	public static final AttributeInfo ATTACK_SPEED = builtin(
		ResourceLocation.withDefaultNamespace("attack_speed"),
		net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED.value(), 4.0f);
	/** 内建：护甲（默认 0）。 */
	public static final AttributeInfo ARMOR = builtin(
		ResourceLocation.withDefaultNamespace("armor"),
		net.minecraft.world.entity.ai.attributes.Attributes.ARMOR.value(), 0.0f);
	/** 内建：护甲韧性（默认 0）。 */
	public static final AttributeInfo ARMOR_TOUGHNESS = builtin(
		ResourceLocation.withDefaultNamespace("armor_toughness"),
		net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS.value(), 0.0f);
	/** 内建：移动速度（默认 0.1）。 */
	public static final AttributeInfo MOVEMENT_SPEED = builtin(
		ResourceLocation.withDefaultNamespace("movement_speed"),
		net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED.value(), 0.1f);
	/** 内建：击退抗性（默认 0）。 */
	public static final AttributeInfo KNOCKBACK_RESISTANCE = builtin(
		ResourceLocation.withDefaultNamespace("knockback_resistance"),
		net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE.value(), 0.0f);

	/** 全局单例（须声明在所有内建字段之后，构造函数 index() 才可见已初始化的字段）。 */
	public static final ChasmAttributes INSTANCE = new ChasmAttributes();

	private final Map<ResourceLocation, AttributeInfo> registry = new ConcurrentHashMap<>();

	private ChasmAttributes() {
		index(ATTACK_DAMAGE);
		index(ATTACK_SPEED);
		index(ARMOR);
		index(ARMOR_TOUGHNESS);
		index(MOVEMENT_SPEED);
		index(KNOCKBACK_RESISTANCE);
	}

	private static AttributeInfo builtin(ResourceLocation id, Attribute attribute, float defaultValue) {
		return new AttributeInfo(id, attribute, defaultValue, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
	}

	private void index(AttributeInfo info) {
		registry.put(info.id(), info);
	}

	/**
	 * 注册一个自定义范围属性（值域 [min,max]，默认 defaultValue）。
	 *
	 * <p>同 {@code build } 时同步写入原版 {@code Registries.ATTRIBUTE}，使
	 * {@code Entity.getAttribute()} 可用。</p>
	 *
	 * @param modId 模组命名空间
	 * @param name  属性注册名
	 * @param defaultValue 默认值
	 * @param min   最小值
	 * @param max   最大值
	 */
	public AttributeInfo registerRanged(String modId, String name, float defaultValue, float min, float max) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		// 1.21 下 Registries.ATTRIBUTE 为 ResourceKey；实际可写的注册表是 BuiltInRegistries.ATTRIBUTE，
		// 用 (Registry<V>, ResourceLocation, V) 重载写入原版属性注册表，使 Entity.getAttribute() 可用。
		Attribute attr = new RangedAttribute("attribute.name." + id.toLanguageKey(), defaultValue, min, max)
			.setSyncable(true);
		Registry.register(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE, id, attr);
		AttributeInfo info = new AttributeInfo(id, attr, defaultValue, min, max);
		index(info);
		ChasmLogger.info(modId, "注册自定义属性 {} {} (default={}, range=[{},{}])", id, defaultValue, min, max);
		return info;
	}

	/**
	 * 注册一个自定义范围属性（Fabric 提供的玩法注册期钩子内）。
	 *
	 * <p>用于在正确的注册阶段（{@code FabricItemGroupEvents}|实体属性挂勾前）注册属性。
	 * 回调内返回的 AttributeInfo 即原版 {@code Registries.ATTRIBUTE} 注册后的投影。</p>
	 *
	 * @param modId   模组命名空间
	 * @param name    属性注册名
	 * @param consumer 注册期消费回调（可在此把属性挂到实体类型上）
	 * @return AttributeInfo（注册后的投影）
	 */
	public AttributeInfo registerHook(String modId, String name, float defaultValue, float min, float max,
		Consumer<AttributeInfo> consumer) {
		AttributeInfo info = registerRanged(modId, name, defaultValue, min, max);
		consumer.accept(info);
		return info;
	}

	/** 按 id 查询属性（含内建）。 */
	public Optional<AttributeInfo> get(ResourceLocation id) {
		return Optional.ofNullable(registry.get(id));
	}

	/** 按 id 查询（void 安全包装，未注册抛异常）。 */
	public AttributeInfo getOrThrow(ResourceLocation id) {
		AttributeInfo info = registry.get(id);
		if (info == null) {
			throw new IllegalArgumentException("未注册的属性: " + id);
		}
		return info;
	}

	/** 按名称路径查询（默认命名空间 minecraft）。 */
	public Optional<AttributeInfo> get(String name) {
		ResourceLocation id = name.contains(":")
			? ResourceLocation.parse(name)
			: ResourceLocation.withDefaultNamespace(name);
		return get(id);
	}

	/** 当前注册的属性总数（含内建）。 */
	public int size() {
		return registry.size();
	}
}