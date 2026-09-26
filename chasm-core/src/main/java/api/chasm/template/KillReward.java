package api.chasm.template;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

import java.util.List;
import java.util.function.Consumer;

/**
 * 击杀奖励（注册制玩法对象）：封装「击杀效果回调 + 击杀掉落列表」。
 *
 * <p>由 {@code ChasmTemplates.killReward(modId, name)} 声明并注册；通过
 * {@code ItemBuilder.killReward(id)} 绑定到物品栈的 {@code KILL_REWARD_ID} 组件。
 * 服务端 {@code AFTER_DEATH} 监听器在玩家用该物品击杀实体时反查并执行。</p>
 *
 * <p>槽位（第十四步 T-A1 提炼自 DaggerOfGreed/WhisperOfTheAbyss）：</p>
 * <ul>
 *   <li>{@code onKill}：击杀效果（任意玩法逻辑，如给击杀者加状态/回血）</li>
 *   <li>{@code drops}：击杀掉落（物品 + 数量区间 + 概率），仿「存活掉 1，击杀掉 3-8」</li>
 * </ul>
 */
public final class KillReward {

	private final ResourceLocation id;
	private final Consumer<KillContext> onKill;
	private final List<KillDrop> drops;

	KillReward(ResourceLocation id, Consumer<KillContext> onKill, List<KillDrop> drops) {
		this.id = id;
		this.onKill = onKill;
		this.drops = List.copyOf(drops);
	}

	/** 击杀奖励注册 id（{@code modId:name}）。 */
	public ResourceLocation id() {
		return id;
	}

	/** 击杀效果回调（可为 null）。 */
	public Consumer<KillContext> onKill() {
		return onKill;
	}

	/** 击杀掉落列表（只读）。 */
	public List<KillDrop> drops() {
		return drops;
	}

	/**
	 * 执行击杀奖励：先跑击杀效果，再按概率结算掉落。
	 *
	 * <p>整体 try-catch 兜底：任一效果/掉落异常不影响其余项与游戏主线程（与 Trait 执行同策略）。</p>
	 *
	 * @param ctx 击杀上下文（由监听器构建，仅服务端）
	 */
	public void apply(KillContext ctx) {
		if (onKill != null) {
			try {
				onKill.accept(ctx);
			} catch (Exception e) {
				ChasmLogger.error(id.getNamespace(), "KillReward {} 击杀效果执行异常", id, e);
			}
		}
		net.minecraft.world.level.Level world = ctx.level();
		for (KillDrop drop : drops) {
			try {
				if (drop.chance() >= 1.0f || world.random.nextFloat() < drop.chance()) {
					int count = drop.min() + world.random.nextInt(drop.max() - drop.min() + 1);
					ctx.spawnItemDrop(drop.item(), count);
				}
			} catch (Exception e) {
				ChasmLogger.error(id.getNamespace(), "KillReward {} 掉落 {} 结算异常", id, drop.item(), e);
			}
		}
	}

	/** 单条击杀掉落声明：物品 + 数量区间 [min,max] + 掉落概率 [0,1]。 */
	public record KillDrop(ItemLike item, int min, int max, float chance) {

		public KillDrop {
			if (min < 0 || max < min) {
				throw new IllegalArgumentException("掉落数量区间非法: min=" + min + ", max=" + max);
			}
			if (chance < 0.0f || chance > 1.0f) {
				throw new IllegalArgumentException("掉落概率非法: " + chance + "（须在 [0,1]）");
			}
		}
	}
}
