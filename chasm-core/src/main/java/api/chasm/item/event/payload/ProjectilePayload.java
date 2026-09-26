package api.chasm.item.event.payload;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;

/** 弹射物命中负载（射箭等远程攻击的实际命中时刻）。 */
public record ProjectilePayload(Entity shooter, Projectile projectile, DamageSource source,
								LivingEntity victim, float damage) {
}
