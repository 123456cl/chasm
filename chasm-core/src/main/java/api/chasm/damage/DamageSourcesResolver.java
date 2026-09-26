package api.chasm.damage;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 把声明式 {@link DamageTypeInfo} 解析为原版 {@link DamageSource} 的映射器。
 *
 * <p>这是"声明式 → 原生"的桥：优先使用<b>真实注册的 DamageType</b>（DataGen 已把声明
 * 落盘为 {@code data/<ns>/damage_type/*.json}，游戏内该类型在动态注册表中存在），使自定义
 * 死亡消息/缩放/护甲等语义由原版 {@link DamageType} 及其标签直接承担；仅当该类型尚未注册
 * （如 DataGen 环境）时，回退为按 {@link DamageTypeInfo#kind()} 调用原版便捷方法，
 * 从而被原版护甲、附魔、魔抗等机制正常结算，同时也经受击免疫帧控制。</p>
 */
public final class DamageSourcesResolver {

	private DamageSourcesResolver() {
	}

	/**
	 * 依据伤害类型的来源类别解析对应的 DamageSource。
	 *
	 * @param sourceEntity 触发来源实体（玩家属性攻击需其本身；否则求生侧对象）
	 * @param info         已注册的伤害类型元数据
	 */
	public static DamageSource resolve(LivingEntity sourceEntity, DamageTypeInfo info) {
		// 1) 优先：真实注册的类型（DataGen 落盘后动态注册表中存在）→ 用其 Holder 直接生成。
		//    自定义 message_id / scaling / effects / 语义标签（bypasses_armor 等）全部生效。
		Holder<DamageType> registered = sourceEntity.level()
			.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
			.getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, info.id()))
			.orElse(null);
		if (registered != null) {
			// 攻击者本身即直接/引发来源（玩家攻击语义等价原版 playerAttack(player)）
			return new DamageSource(registered, sourceEntity);
		}
		// 2) 回退：类型尚未落盘（DataGen/静态期）时按来源类别映射到原版 DamageSources
		DamageSources ds = sourceEntity.damageSources();
		// 若是玩家，尝试其属性的战斗 damageSources（含玩家攻击特殊逻辑）
		if (sourceEntity instanceof Player player) {
			switch (info.kind()) {
				case PLAYER_ATTACK -> {
					return ds.playerAttack(player);
				}
				case MAGIC, INDIRECT_MAGIC -> {
					return ds.indirectMagic(player, player);
				}
				case FIRE -> {
					return ds.onFire();
				}
				case LIGHTNING -> {
					return ds.lightningBolt();
				}
				case MOB_ATTACK -> {
					// 玩家无 mobAttack；退化为玩家攻击类别
					return ds.playerAttack(player);
				}
			}
		}
		// 非玩家攻击者（普通生物/掉落物等）
		switch (info.kind()) {
			case PLAYER_ATTACK, MOB_ATTACK -> {
				return ds.mobAttack(sourceEntity);
			}
			case MAGIC, INDIRECT_MAGIC -> {
				return ds.indirectMagic(sourceEntity, sourceEntity);
			}
			case FIRE -> {
				return ds.onFire();
			}
			case LIGHTNING -> {
				return ds.lightningBolt();
			}
			default -> {
				return ds.generic();
			}
		}
	}

	/** 便捷重载：以 entity 自身为攻击者解析来源（用于非攻击方上下文）。 */
	public static DamageSource resolve(Entity entity, DamageTypeInfo info) {
		if (entity instanceof LivingEntity living) {
			return resolve(living, info);
		}
		return entity.damageSources().generic();
	}
}