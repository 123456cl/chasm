package api.chasm.damage;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 伤害类型声明 Builder（链式、声明式）。
 *
 * <p>用法：</p>
 * <pre>{@code
 * ChasmDamageTypes.register("mymod", "arcane_burn")
 *     .parent(DamageTypeKind.MAGIC)      // 继承魔法元数据为默认值（扁平实例）
 *     .message("was consumed by arcane fire")
 *     .bypassesArmor(true)
 *     .tag("magic").tag("fire")
 *     .build();
 * }</pre>
 *
 * <p>{@link #parent(DamageTypeKind)} 或 {@link #parent(ResourceLocation)} 是一种"元数据拷贝"：
 * 把父类型的 messageId / exhaustion / bypass / isFire / scaling 作为当前类型的默认值，
 * 而后可被后续调用覆盖；注册产物始终是独立扁平类型（等价于新 JSON，而非 JSON 继承）。</p>
 */
public final class ChasmDamageTypeBuilder {

	private final ResourceLocation id;
	private final DamageTypeKind kind;
	private String messageId;
	private float exhaustion;
	private boolean bypassArmor;
	private boolean isFire;
	private boolean scalesWithDifficulty;
	private final Set<String> tags = new java.util.LinkedHashSet<>();
	/** 回写日志：构建完成时的副作用（可选）。 */
	private BiConsumer<DamageTypeInfo, ResourceLocation> onBuild;

	ChasmDamageTypeBuilder(ResourceLocation id, DamageTypeKind kind) {
		this.id = id;
		this.kind = kind;
		// kind 的默认元数据作为兜底
		DamageTypeInfo.Meta m = kindMetadata(kind);
		this.messageId = m.messageId();
		this.exhaustion = m.exhaustion();
		this.bypassArmor = m.bypassArmor();
		this.isFire = m.isFire();
		this.scalesWithDifficulty = m.scales();
	}

	/** 拷贝父类型的元数据作为默认值（父可为一内置 kind 或已注册类型）。 */
	public ChasmDamageTypeBuilder parent(DamageTypeKind parent) {
		DamageTypeInfo.Meta meta = kindMetadata(parent);
		this.messageId = meta.messageId();
		this.exhaustion = meta.exhaustion();
		this.bypassArmor = meta.bypassArmor();
		this.isFire = meta.isFire();
		this.scalesWithDifficulty = meta.scales();
		return this;
	}

	/** 覆盖死亡消息 id。 */
	public ChasmDamageTypeBuilder message(String messageId) {
		return messageId(messageId);
	}

	/** 覆盖死亡消息 id。 */
	public ChasmDamageTypeBuilder messageId(String messageId) {
		this.messageId = messageId;
		return this;
	}

	/** 覆盖疲劳系数。 */
	public ChasmDamageTypeBuilder exhaustion(float exhaustion) {
		this.exhaustion = exhaustion;
		return this;
	}

	/** 设置是否无视护甲减免。 */
	public ChasmDamageTypeBuilder bypassesArmor(boolean bypass) {
		this.bypassArmor = bypass;
		return this;
	}

	/** 设置是否火焰伤害。 */
	public ChasmDamageTypeBuilder isFire(boolean isFire) {
		this.isFire = isFire;
		return this;
	}

	/** 设置是否随难度缩放。 */
	public ChasmDamageTypeBuilder scalesWithDifficulty(boolean scale) {
		this.scalesWithDifficulty = scale;
		return this;
	}

	/** 追加聚合标签。 */
	public ChasmDamageTypeBuilder tag(String tag) {
		this.tags.add(tag);
		return this;
	}

	/** 追加多个聚合标签。 */
	public ChasmDamageTypeBuilder tags(String... tags) {
		for (String t : tags) {
			this.tags.add(t);
		}
		return this;
	}

	/** 注册到全局注册表并返回其元数据（注册产物为独立扁平实例）。 */
	public DamageTypeInfo build() {
		DamageTypeInfo info = new DamageTypeInfo(id, kind, messageId, exhaustion,
			bypassArmor, isFire, scalesWithDifficulty, tags);
		ChasmDamageTypes.INSTANCE.put(info);
		if (onBuild != null) {
			onBuild.accept(info, id);
		}
		return info;
	}

	/** 构建后日志钩子（框架内部用）。 */
	ChasmDamageTypeBuilder onBuild(BiConsumer<DamageTypeInfo, ResourceLocation> callback) {
		this.onBuild = callback;
		return this;
	}

	private static DamageTypeInfo.Meta kindMetadata(DamageTypeKind k) {
		return switch (k) {
			case PLAYER_ATTACK -> new DamageTypeInfo.Meta("player", 0.1f, false, false, false);
			case MOB_ATTACK -> new DamageTypeInfo.Meta("mob", 0.1f, false, false, true);
			case MAGIC -> new DamageTypeInfo.Meta("magic", 0.0f, true, false, true);
			case FIRE -> new DamageTypeInfo.Meta("onFire", 0.1f, false, true, false);
			case LIGHTNING -> new DamageTypeInfo.Meta("lightningBolt", 0.1f, false, false, false);
			case INDIRECT_MAGIC -> new DamageTypeInfo.Meta("indirectMagic", 0.0f, true, false, true);
		};
	}
}