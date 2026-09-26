package api.chasm.effect;

/**
 * **数值型伤害修正**（不可变值对象）—— 伤害事件层唯一允许"改数值"的载体。
 *
 * <p>为什么需要它：框架原有的事件协议是布尔的（{@code ON_INCOMING_DAMAGE} 只能"放行/取消"），
 * 而真实 Tetra 的一批效果改的是**伤害数值本身**，且分两个阶段：</p>
 * <ul>
 *   <li><b>护甲结算之前</b>（真实 Forge 的 {@code LivingHurtEvent}）：
 *       {@code quickStrike} 把低于"下限"的伤害抬上去（{@code ItemEffectHandler.java:214-223}）；
 *       {@code armorPenetration} 给目标挂临时 ARMOR 修饰符，让**这一次**伤害少算护甲
 *       （{@code ArmorPenetrationEffect.java:25-33}，{@code amount = -level x 0.01}，{@code MULTIPLY_TOTAL}）。</li>
 *   <li><b>护甲结算之后</b>（真实 {@code LivingDamageEvent}）：
 *       {@code crushing}（{@code CrushingEffect.java:11-20}）、{@code skewering}（{@code SkeweringEffect.java:10-14}）、
 *       {@code reaching}（{@code ReachingEffect.java:29-37}）。</li>
 * </ul>
 *
 * <p>四个通道各自的语义（与真实实现逐条对应，不是自造）：</p>
 * <table border=1>
 * <tr><th>通道</th><th>语义</th><th>真实依据</th></tr>
 * <tr><td>{@code flat}</td><td>加法增量（可负）</td><td>skewering 的 {@code event.setAmount(amount + level)}（SkeweringEffect.java:13）</td></tr>
 * <tr><td>{@code multiplier}</td><td>乘算（累乘，默认 1）</td><td>reaching 的 {@code amount *= multiplier}（ReachingEffect.java:35）</td></tr>
 * <tr><td>{@code floor}</td><td>伤害下限（低于则抬到该值）</td><td>quickStrike（ItemEffectHandler.java:220-222）、crushing（CrushingEffect.java:14-17）</td></tr>
 * <tr><td>{@code armorPenetration}</td><td>护甲无视比例 0..1（累加，上限 1）</td><td>ArmorPenetrationEffect.java:32 的 {@code -level x 0.01 MULTIPLY_TOTAL}</td></tr>
 * </table>
 *
 * <p><b>求值顺序</b>：{@code apply(base) = max(0, max(floor, (base + flat) x multiplier))}。
 * 与真实一致：{@code floor} 是"最终下限"，所以 crusading/quickStrike 抬起来的数值不再被负乘算压回去
 * （真实里两者都是"直接 set 数值"，不是参与后续乘算）。</p>
 *
 * <p>复杂度：纯 record，全部方法 O(1)、零分配（除 {@code merge} 返回一个新 record）。</p>
 *
 * @param flat              加法增量（0 = 不加）
 * @param multiplier        乘算倍率（1 = 不变；累乘）
 * @param floor             伤害下限（0 = 无下限）
 * @param armorPenetration  护甲无视比例（0..1，累加后钳制）
 */
public record DamageNumber(float flat, float multiplier, float floor, float armorPenetration) {

	/** 中性值：什么都不改（{@code apply(base) == base}）。 */
	public static final DamageNumber NEUTRAL = new DamageNumber(0.0F, 1.0F, 0.0F, 0.0F);

	/** 规范化：NaN/无穷一律回落到中性值，倍率 ≤ 0 视为 1（**绝不产生 0 伤害或 NaN**）。 */
	public DamageNumber {
		if (!Float.isFinite(flat)) {
			flat = 0.0F;
		}
		if (!Float.isFinite(multiplier) || multiplier <= 0.0F) {
			multiplier = 1.0F;
		}
		if (!Float.isFinite(floor) || floor < 0.0F) {
			floor = 0.0F;
		}
		if (!Float.isFinite(armorPenetration)) {
			armorPenetration = 0.0F;
		}
		armorPenetration = Math.max(0.0F, Math.min(1.0F, armorPenetration));
	}

	/** 追加加法增量。 */
	public DamageNumber add(float value) {
		return new DamageNumber(flat + value, multiplier, floor, armorPenetration);
	}

	/** 追乘算倍率（{@code 1} = 不变；{@code <= 0} 与 NaN 被规范化忽略）。 */
	public DamageNumber times(float value) {
		return new DamageNumber(flat, multiplier * value, floor, armorPenetration);
	}

	/** 抬高伤害下限（取较大者，**不会降低**已有的下限）。 */
	public DamageNumber atLeast(float value) {
		return new DamageNumber(flat, multiplier, Math.max(floor, value), armorPenetration);
	}

	/** 追加护甲无视比例（累加并钳到 0..1）。 */
	public DamageNumber penetrate(float value) {
		return new DamageNumber(flat, multiplier, floor, armorPenetration + value);
	}

	/**
	 * 合并两次修正（协议 {@code merge} 用）：加法相加、乘算连乘、下限取大、护甲无视相加后钳制。
	 * 顺序无关（与真实"多个效果各自 setAmount"相比，这里是可交换的累积语义，更稳定）。
	 */
	public DamageNumber merge(DamageNumber other) {
		if (other == null) {
			return this;
		}
		return new DamageNumber(flat + other.flat, multiplier * other.multiplier,
			Math.max(floor, other.floor), armorPenetration + other.armorPenetration);
	}

	/** 把修正作用到原始伤害上（结果恒 ≥ 0）。 */
	public float apply(float base) {
		float safeBase = Float.isFinite(base) && base > 0.0F ? base : 0.0F;
		return Math.max(0.0F, Math.max(floor, (safeBase + flat) * multiplier));
	}

	/** 是否什么都没改（事件源可据此零开销早退）。 */
	public boolean isNeutral() {
		return flat == 0.0F && multiplier == 1.0F && floor == 0.0F && armorPenetration == 0.0F;
	}

	/** 变化量（正 = 加伤，负 = 减伤）：{@code apply(base) - base}。 */
	public float delta(float base) {
		float safeBase = Float.isFinite(base) && base > 0.0F ? base : 0.0F;
		return apply(safeBase) - safeBase;
	}

	@Override
	public String toString() {
		return "DamageNumber[+ " + flat + ", x " + multiplier + ", >= " + floor + ", pen " + armorPenetration + "]";
	}
}
