package api.chasm.template;

import api.chasm.item.context.AttackContext;
import api.chasm.log.ChasmLogger;
import api.chasm.trait.ChasmTraits;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 武器能力模板（第十四步 T-A1，提炼自 Aquamirae DaggerOfGreed / WhisperOfTheAbyss）。
 *
 * <p>把一把玩法武器的常见槽位收敛成一次声明：</p>
 * <ul>
 *   <li>{@code attackDamage / attackSpeed}：可选的基础属性（0 = 不覆盖物品自身声明）</li>
 *   <li>{@code onHit}：命中效果（经内部自动注册的 {@code AttackTrait} 在每击触发，
 *       保留原版物理伤害结算）</li>
 *   <li>{@code onKill}：击杀效果（经内部自动注册的 {@code KillReward} 在击杀时触发）</li>
 *   <li>{@code killDrop}：击杀掉落（物品 + 数量区间 + 概率）</li>
 * </ul>
 *
 * <p>构建时（{@code build()}）自动注册两件事：命中特质（{@code modId:name_hit}）与
 * 击杀奖励（{@code modId:name_kill}）；模板本身是普通对象，经
 * {@code ItemBuilder.template(template)} 把上述绑定一次性应用到物品。</p>
 *
 * <p>一个模板可被多把武器复用（改改就能直接用），例如「贪婪」类模板绑定多把武器都掉绿宝石。</p>
 */
public final class WeaponTemplate {

	private final ResourceLocation id;
	private final ResourceLocation hitTraitId;
	private final ResourceLocation killRewardId;
	private final float attackDamage;
	private final float attackSpeed;

	WeaponTemplate(ResourceLocation id, ResourceLocation hitTraitId, ResourceLocation killRewardId,
		float attackDamage, float attackSpeed) {
		this.id = id;
		this.hitTraitId = hitTraitId;
		this.killRewardId = killRewardId;
		this.attackDamage = attackDamage;
		this.attackSpeed = attackSpeed;
	}

	/** 模板注册 id（{@code modId:name}，仅用于日志/标识）。 */
	public ResourceLocation id() {
		return id;
	}

	/** 自动注册的命中特质 id（未声明 onHit 时为 null）。 */
	public ResourceLocation hitTraitId() {
		return hitTraitId;
	}

	/** 自动注册的击杀奖励 id（未声明 onKill/掉落时为 null）。 */
	public ResourceLocation killRewardId() {
		return killRewardId;
	}

	/** 模板携带的基础攻击力（0 = 不覆盖物品自身声明）。 */
	public float attackDamage() {
		return attackDamage;
	}

	/** 模板携带的总攻击速度（0 = 不覆盖物品自身声明）。 */
	public float attackSpeed() {
		return attackSpeed;
	}

	/** 武器模板链式构建器：{@code attackDamage/attackSpeed/onHit/onKill/killDrop} 组合后 {@code build()} 自动注册。 */
	public static final class Builder {

		private final String modId;
		private final String name;
		private float attackDamage;
		private float attackSpeed;
		private Consumer<AttackContext> onHit;
		private Consumer<KillContext> onKill;
		private final List<KillReward.KillDrop> drops = new ArrayList<>();

		Builder(String modId, String name) {
			this.modId = modId;
			this.name = name;
		}

		/** 模板默认基础攻击力（{@code ItemBuilder.template} 时应用；0 = 不覆盖）。 */
		public Builder attackDamage(float damage) {
			this.attackDamage = damage;
			return this;
		}

		/** 模板默认总攻击速度（{@code ItemBuilder.template} 时应用；0 = 不覆盖）。 */
		public Builder attackSpeed(float speed) {
			api.chasm.attribute.ChasmAttackSpeed.requireSaneTotal(speed, "武器模板攻击速度声明");
			this.attackSpeed = speed;
			return this;
		}

		/** 命中效果：每击触发，叠加在原版物理伤害之上（保留原版结算）。 */
		public Builder onHit(Consumer<AttackContext> handler) {
			this.onHit = handler;
			return this;
		}

		/** 击杀效果：击杀实体时触发（服务端）。 */
		public Builder onKill(Consumer<KillContext> handler) {
			this.onKill = handler;
			return this;
		}

		/** 击杀掉落：固定数量、必掉。 */
		public Builder killDrop(ItemLike item, int count) {
			return killDrop(item, count, count, 1.0f);
		}

		/** 击杀掉落：数量区间、必掉。 */
		public Builder killDrop(ItemLike item, int min, int max) {
			return killDrop(item, min, max, 1.0f);
		}

		/** 击杀掉落：数量区间 + 概率 [0,1]。 */
		public Builder killDrop(ItemLike item, int min, int max, float chance) {
			drops.add(new KillReward.KillDrop(item, min, max, chance));
			return this;
		}

		/** 构建并自动注册（命中特质 + 击杀奖励），返回可复用的武器模板。 */
		public WeaponTemplate build() {
			ResourceLocation base = ResourceLocation.fromNamespaceAndPath(modId, name);
			// 1) 命中效果 → 自动注册 AttackTrait（保留原版物理伤害，效果叠加其上）
			ResourceLocation hitTraitId = null;
			if (onHit != null) {
				ResourceLocation traitId = ResourceLocation.fromNamespaceAndPath(modId, name + "_hit");
				ChasmTraits.INSTANCE.registerAttack(modId, name + "_hit", ctx -> {
					try {
						onHit.accept(ctx);
					} catch (Exception e) {
						ChasmLogger.error(modId, "武器模板 {} 命中效果执行异常", base, e);
					}
					return InteractionResult.PASS; // 命中效果叠加在原版伤害之上，不消费本次攻击
				});
				hitTraitId = traitId;
			}
			// 2) 击杀效果 + 掉落 → 自动注册 KillReward
			ResourceLocation killRewardId = null;
			if (onKill != null || !drops.isEmpty()) {
				ChasmKillRewards.Builder kb = ChasmKillRewards.INSTANCE.create(modId, name + "_kill");
				if (onKill != null) {
					kb.onKill(onKill);
				}
				for (KillReward.KillDrop drop : drops) {
					kb.drop(drop.item(), drop.min(), drop.max(), drop.chance());
				}
				killRewardId = kb.build().id();
			}
			ChasmLogger.info(modId, "构建武器模板 {}（命中={}, 击杀={}, 攻= {}, 速= {}）",
				base, hitTraitId, killRewardId, attackDamage, attackSpeed);
			return new WeaponTemplate(base, hitTraitId, killRewardId, attackDamage, attackSpeed);
		}
	}
}
