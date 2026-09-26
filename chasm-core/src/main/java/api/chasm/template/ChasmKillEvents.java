package api.chasm.template;

import api.chasm.data.ChasmData;
import api.chasm.log.ChasmLogger;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 击杀事件监听（第十四步模板层：KillReward 的执行入口）。
 *
 * <p>复用 Fabric API 的 {@link ServerLivingEntityEvents#AFTER_DEATH}（服务端主线程事件），
 * 不自制总线。监听逻辑：</p>
 * <ol>
 *   <li>实体死亡（服务端）</li>
 *   <li>伤害来源 {@code getEntity()} 是玩家（间接击杀如箭矢也能取到射手）</li>
 *   <li>击杀者主手物品栈携带 {@code KILL_REWARD_ID} 组件</li>
 *   <li>O(1) 反查 {@link KillReward} 并 {@code apply}</li>
 * </ol>
 *
 * <p>整体 try-catch 兜底：任何异常只记录日志，不影响死亡结算与主线程（与 Trait 执行同策略）。</p>
 */
public final class ChasmKillEvents {

	private ChasmKillEvents() {
	}

	/** 注册击杀监听（由 {@code ChasmInit} 调用一次，两侧都安全：客户端事件永不触发）。 */
	public static void init() {
		ServerLivingEntityEvents.AFTER_DEATH.register(ChasmKillEvents::handleDeath);
		ChasmLogger.call("chasm", "ChasmKillEvents", "init", "击杀奖励监听已注册（AFTER_DEATH）");
	}

	private static void handleDeath(LivingEntity victim, DamageSource source) {
		if (victim.level().isClientSide || source == null) {
			return;
		}
		Entity attacker = source.getEntity(); // 间接击杀（箭矢等）取到的也是负责任实体
		if (!(attacker instanceof Player killer)) {
			return;
		}
		ItemStack stack = killer.getMainHandItem();
		ResourceLocation rewardId = stack.get(ChasmData.KILL_REWARD_ID);
		if (rewardId == null) {
			return;
		}
		KillReward reward = ChasmKillRewards.INSTANCE.get(rewardId);
		if (reward == null) {
			ChasmLogger.error(rewardId.getNamespace(), "击杀奖励 {} 未注册，跳过", rewardId);
			return;
		}
		try {
			KillContext ctx = new KillContext(stack, victim, killer, source);
			reward.apply(ctx);
		} catch (Exception e) {
			ChasmLogger.error(rewardId.getNamespace(), "击杀奖励 {} 执行异常", rewardId, e);
		}
	}
}
