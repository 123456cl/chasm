package api.chasm.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * **可插拔的自定义药水效果基类**：把"何时 tick / tick 时做什么 / 是否瞬时"三件事做成构造期参数，
 * 于是注册一个效果不再需要为每个效果写一个类。
 *
 * <p>真实依据（Tetra 的 4 个效果各自是一个类，但结构完全同构）：</p>
 * <ul>
 *   <li>{@code BleedingPotionEffect.java:35-51}：{@code HARMFUL, 0x880000}；每 10 tick 造成 {@code amplifier} 点伤害；</li>
 *   <li>{@code StunPotionEffect.java:24-51}：{@code HARMFUL, 0xeeeeee} + 三条属性修饰符；每 4 tick 撒粒子；</li>
 *   <li>{@code EarthboundPotionEffect.java:15-22}：{@code HARMFUL, 0x006600} + 移速 −30% / 击退抗性 +1；</li>
 *   <li>{@code SeveredPotionEffect.java:26-50}：{@code HARMFUL, 0x880000} + 最大生命 −10% / 攻击力 −5%；每 10 tick 粒子。</li>
 * </ul>
 *
 * <p>它们在 1.20/Forge 里覆写的是 {@code isDurationEffectTick}；1.21.1 的原版方法名是
 * {@code shouldApplyEffectTickThisTick}（{@code MobEffect} 的原版签名，反汇编确认），
 * 本类对接的是后者。</p>
 *
 * <p>复杂度：全是 O(1) 转发；没有分配（除效果实例本身）。</p>
 */
public class ChasmMobEffect extends MobEffect {

	/** tick 行为：{@code entity} 为携带者、{@code amplifier} 为等级（0 基）。返回 false 表示"本 tick 不生效"。 */
	@FunctionalInterface
	public interface TickFn {
		boolean apply(LivingEntity entity, int amplifier);
	}

	/** tick 间隔判定（真实 Tetra 的 {@code isDurationEffectTick} 等价物）。 */
	@FunctionalInterface
	public interface IntervalFn {
		boolean shouldApply(int duration, int amplifier);
	}

	private final TickFn tick;
	private final IntervalFn interval;
	private final boolean instant;

	protected ChasmMobEffect(MobEffectCategory category, int color, boolean instant, IntervalFn interval, TickFn tick) {
		super(category, color);
		this.instant = instant;
		this.interval = interval;
		this.tick = tick;
	}

	/** 是否瞬时（与 {@code InstantenousMobEffect} 逐字一致：{@code true} + 每 tick 都生效）。 */
	@Override
	public boolean isInstantenous() {
		return instant;
	}

	@Override
	public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
		if (instant) {
			// 反汇编 InstantenousMobEffect#shouldApplyEffectTickThisTick：duration >= 1
			return duration >= 1;
		}
		if (interval != null) {
			return interval.shouldApply(duration, amplifier);
		}
		return super.shouldApplyEffectTickThisTick(duration, amplifier);
	}

	@Override
	public boolean applyEffectTick(LivingEntity entity, int amplifier) {
		if (tick == null) {
			return super.applyEffectTick(entity, amplifier);
		}
		return tick.apply(entity, amplifier);
	}
}
