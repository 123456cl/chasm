package api.chasm.item.event.payload;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** 命中方向的负载（攻击者视角，命中已结算）。 */
public record HitPayload(Entity attacker, LivingEntity victim, DamageSource source,
						float originalDamage, float finalDamage) {
}
