package api.chasm.item.event.payload;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * **伤害结算前（护甲之前）的数值负载**：事件源在扣血之前发起，监听者可改数值/削护甲。
 *
 * <p>与 {@link DamagePayload}（受击者视角的布尔事件）的区别：本负载带着**攻击者**与**当前伤害数值**，
 * 配合 {@link api.chasm.effect.DamageNumber} 的协议可以改数值。</p>
 *
 * <p>真实依据：Forge 的 {@code LivingHurtEvent}（护甲换算之前）——
 * 真实 Tetra 在这个阶段做 {@code quickStrike}（{@code ItemEffectHandler.java:214-223}）
 * 与 {@code armorPenetration}（{@code :225-228} + {@code ArmorPenetrationEffect.java:25-33}）。</p>
 *
 * @param victim   受击者
 * @param source   伤害来源
 * @param attacker 造成伤害的实体（{@code source.getEntity()}；可能为 null，如摔落/火焰）
 * @param amount   当前伤害数值（尚未过护甲）
 */
public record HurtPayload(LivingEntity victim, DamageSource source, LivingEntity attacker, float amount) {
}
