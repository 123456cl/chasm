package api.chasm.damage;

/**
 * 伤害类型的内置"来源类别"，用于把声明的伤害类型映射到原版
 * {@link net.minecraft.world.damagesource.DamageSource}（避免手写 1000 行 JSON）。
 *
 * <p>每个枚举对应 Player 提供的 {@code damageSources().xxx()} 便捷方法；
 * 真正确认应用到哪个 DamageSource 在 {@link DamageTypeInfo#sourceFactory()}。</p>
 */
public enum DamageTypeKind {

	/** 玩家近战攻击（受护甲减免）。 */
	PLAYER_ATTACK,
	/** 普通生物攻击（受护甲减免）。 */
	MOB_ATTACK,
	/** 魔法伤害（无视护甲减免，受魔抗影响）。 */
	MAGIC,
	/** 火焰伤害（附带着火标记）。 */
	FIRE,
	/** 雷电伤害。 */
	LIGHTNING,
	/** 间接魔法（如药水/法术弹射）。 */
	INDIRECT_MAGIC
}