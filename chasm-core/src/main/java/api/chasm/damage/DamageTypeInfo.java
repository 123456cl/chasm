package api.chasm.damage;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一个已注册的伤害类型的不可变元数据（"一等公民"）。
 *
 * <p>对应原版数据驱动的 {@code data/<namespace>/damage_type/*.json}，但用代码声明式定义，
 * 并额外维护聚合标签（tag）与来源类别（{@link DamageTypeKind}），供跨模组查询与聚合。</p>
 *
 * <p>注意：原版 DamageType 是扁平 JSON，没有"父类型继承"；我们在注册时通过
 * {@link ChasmDamageTypeBuilder#parent(ResourceLocation)} 从父类型的元数据<em>拷贝默认值</em>，
 * 注册产物始终是独立扁平实例。本类即该扁平实例的内存投影。</p>
 */
public final class DamageTypeInfo {

	private final ResourceLocation id;
	private final String modId;
	private final DamageTypeKind kind;
	private final String messageId;
	private final float exhaustion;
	private final boolean bypassArmor;
	private final boolean isFire;
	private final boolean scalesWithDifficulty;
	private final Set<String> tags;

	DamageTypeInfo(ResourceLocation id, DamageTypeKind kind, String messageId, float exhaustion,
		boolean bypassArmor, boolean isFire, boolean scalesWithDifficulty, Set<String> tags) {
		this.id = Objects.requireNonNull(id, "id");
		this.modId = id.getNamespace();
		this.kind = Objects.requireNonNull(kind, "kind");
		this.messageId = Objects.requireNonNull(messageId, "messageId");
		this.exhaustion = exhaustion;
		this.bypassArmor = bypassArmor;
		this.isFire = isFire;
		this.scalesWithDifficulty = scalesWithDifficulty;
		this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
	}

	/** 唯一标识符（{@code namespace:path}）。 */
	public ResourceLocation id() {
		return id;
	}

	/** 来源模组命名空间。 */
	public String modId() {
		return modId;
	}

	/** 原版 DamageType 的消息 id（如 {@code magic}）。 */
	public String messageId() {
		return messageId;
	}

	/** 来源类别。 */
	public DamageTypeKind kind() {
		return kind;
	}

	/** 疲劳系数（原版 DamageType.exhaustion）。 */
	public float exhaustion() {
		return exhaustion;
	}

	/** 是否无视护甲减免。 */
	public boolean bypassesArmor() {
		return bypassArmor;
	}

	/** 是否属于火焰伤害（触发出火/引燃逻辑）。 */
	public boolean isFire() {
		return isFire;
	}

	/** 是否随难度缩放伤害（原版 DamageTypeScaling）。 */
	public boolean scalesWithDifficulty() {
		return scalesWithDifficulty;
	}

	/** 聚合标签（如 "magic"、"fire"），供整合包统一归类。 */
	public Set<String> tags() {
		return tags;
	}

	/** 是否带有某聚合标签。 */
	public boolean hasTag(String tag) {
		return tags.contains(tag);
	}

	/** 某来源类别的元数据模板（用于 builder 的 parent 拷贝默认值）。 */
	record Meta(String messageId, float exhaustion, boolean bypassArmor, boolean isFire, boolean scales) {
	}
}