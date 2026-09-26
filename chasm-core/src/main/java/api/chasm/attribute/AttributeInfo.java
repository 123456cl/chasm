package api.chasm.attribute;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;

/**
 * 一个已注册属性（含原版内置属性的投影）的元数据。
 *
 * <p>仅持有指向实际 {@link Attribute} 的引用与元信息；真正生效仍需写入物品的
 * {@code ATTRIBUTE_MODIFIERS} 组件（由 {@code ItemBuilder.attribute()} 完成）。
 * 本类用于跨模组查询：拿到属性 id 后可读该属性所在物品的修饰符值。</p>
 */
public final class AttributeInfo {

	private final ResourceLocation id;
	private final String modId;
	private final Attribute attribute;
	private final float defaultValue;
	private final float min;
	private final float max;

	AttributeInfo(ResourceLocation id, Attribute attribute, float defaultValue, float min, float max) {
		this.id = id;
		this.modId = id.getNamespace();
		this.attribute = attribute;
		this.defaultValue = defaultValue;
		this.min = min;
		this.max = max;
	}

	/** 属性唯一标识符（{@code namespace:path}）。 */
	public ResourceLocation id() {
		return id;
	}

	/** 来源模组命名空间。 */
	public String modId() {
		return modId;
	}

	/** 实际的原版 {@link Attribute} 引用（可用于读取实体属性值）。 */
	public Attribute attribute() {
		return attribute;
	}

	/** 默认值（玩家空手/未装备时该属性的基础值，用于声明 total 时反推修饰符）。 */
	public float defaultValue() {
		return defaultValue;
	}

	public float min() {
		return min;
	}

	public float max() {
		return max;
	}
}