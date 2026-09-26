package api.chasm.item.event.payload;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** 盾挡负载（本次伤害被盾牌/格挡减免）。 */
public record ShieldBlockPayload(LivingEntity blocker, DamageSource source, float blockedAmount,
								 float baseDamage) {
}
