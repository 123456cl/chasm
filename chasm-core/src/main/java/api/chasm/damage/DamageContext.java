package api.chasm.damage;

import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次伤害计算的完整上下文（把"伤害值 + 暴击率 + 倍率 + 波动"从类型中解耦）。
 *
 * <p>用法：</p>
 * <pre>{@code
 * float finalDamage = new DamageContext(MANA_SWORD_ATTACK_TYPE)
 *     .base(10.0f)
 *     .critChance(0.3f, 2.0f)          // 30% 概率翻倍
 *     .multiplier(1.0f)
 *     .resolve(player, target, random); // 最终伤害
 * }</pre>
 *
 * <p>跨模组可经 {@link DamageTypeInfo} 读取类型的期望语义（是否无视护甲等），
 * 但实际是否 apply 到某个 {@link DamageSource} 由
 * {@link DamageSourcesResolver#resolve(DamageTypeInfo, LivingEntity)} 决定。</p>
 */
public final class DamageContext {

	private final DamageTypeInfo type;
	private float baseDamage;
	private float multiplier = 1.0f;
	private float critChance = 0.0f;
	private float critMultiplier = 1.5f;
	/** 伤害抬手回调（如特殊音效/粒子），apply 前调用。 */
	private final List<Runnable> onApply = new ArrayList<>();

	public DamageContext(DamageTypeInfo type) {
		this.type = type;
	}

	public DamageContext(DamageTypeInfo type, float baseDamage) {
		this(type);
		this.baseDamage = baseDamage;
	}

	/** 设置基础伤害。 */
	public DamageContext base(float baseDamage) {
		this.baseDamage = baseDamage;
		return this;
	}

	/** 设置全局倍率（默认 1.0）。 */
	public DamageContext multiplier(float multiplier) {
		this.multiplier = multiplier;
		return this;
	}

	/** 设置暴击概率与暴击倍率。 */
	public DamageContext critChance(float chance, float critMultiplier) {
		this.critChance = chance;
		this.critMultiplier = critMultiplier;
		return this;
	}

	/** 追加一个 apply 前回调（音效/粒子等副作用）。 */
	public DamageContext onApply(Runnable runnable) {
		this.onApply.add(runnable);
		return this;
	}

	/** 期望伤害（不涉及随机，供查询展示：base * multiplier），不含暴击期望。 */
	public float expected() {
		return baseDamage * multiplier;
	}

	/** 依据随机源计算最终伤害（含暴击判断）。 */
	public float calculate(RandomSource random) {
		float result = baseDamage * multiplier;
		if (critChance > 0.0f && random.nextFloat() < critChance) {
			result *= critMultiplier;
		}
		return result;
	}

	/**
	 * 解析出实际 {@link DamageSource} 并施予目标。
	 *
	 * @param attacker 攻击者（用于 {@code DamageSources} 方法的上下文）
	 * @param target   承受伤害的实体
	 * @param random   随机源
	 * @return 实际造成的伤害值（经护甲/抗性等原版机制换算后的数值由原版返回伤害事件；此处为名义值）
	 */
	public float apply(LivingEntity attacker, LivingEntity target, RandomSource random) {
		for (Runnable r : onApply) {
			r.run();
		}
		float amount = calculate(random);
		DamageSource source = DamageSourcesResolver.resolve(attacker, type);
		if (amount <= 0.0f) {
			return 0.0f;
		}
		target.hurt(source, amount);
		return amount;
	}

	/** 仅解析来源、不施伤害（供工具/查询展示类型映射结果）。 */
	public DamageSource resolveSource(LivingEntity attacker) {
		return DamageSourcesResolver.resolve(attacker, type);
	}

	/** 关联的伤害类型。 */
	public DamageTypeInfo type() {
		return type;
	}
}