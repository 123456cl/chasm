package api.chasm.item.event.payload;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** 受击方向的负载（受击者视角）。 */
public record DamagePayload(LivingEntity victim, DamageSource source, float amount) {
}
