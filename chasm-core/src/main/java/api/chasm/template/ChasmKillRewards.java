package api.chasm.template;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 击杀奖励全局注册表（单例）。
 *
 * <p>与 {@link api.chasm.trait.ChasmTraits} 同构：按「id → 实例」的 {@code ConcurrentHashMap}
 * 维护，运行时 O(1) 反查，零遍历。注册发生在模板声明期（静态初始化），无需时序等待。</p>
 */
public final class ChasmKillRewards {

	/** 全局单例。 */
	public static final ChasmKillRewards INSTANCE = new ChasmKillRewards();

	private final ConcurrentHashMap<ResourceLocation, KillReward> registry = new ConcurrentHashMap<>();

	private ChasmKillRewards() {
	}

	/**
	 * 注册一个击杀奖励（由 {@link ChasmTemplates#killReward(String, String)} 的构建器调用）。
	 *
	 * @return 击杀奖励 id（{@code modId:name}）
	 */
	public ResourceLocation register(String modId, String name, KillReward reward) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		registry.put(id, reward);
		ChasmLogger.info(modId, "注册击杀奖励 {}（效果={}, 掉落 {} 条）",
			id, reward.onKill() != null, reward.drops().size());
		return id;
	}

	/**
	 * 启动一个击杀奖励声明（链式配置后 {@code .build()} 自动注册）。
	 *
	 * <pre>{@code
	 * KillReward reward = Chasm.killRewards().create("mymod", "greed")
	 *     .onKill(ctx -> ctx.killer().heal(1.0f))
	 *     .drop(Items.EMERALD, 3, 8, 1.0f)
	 *     .build();
	 * }</pre>
	 *
	 * @param modId 模组命名空间
	 * @param name  击杀奖励注册名
	 */
	public Builder create(String modId, String name) {
		return new Builder(modId, name);
	}

	/** 按 id 查询击杀奖励（未注册返回 null）。 */
	public KillReward get(ResourceLocation id) {
		return registry.get(id);
	}

	/** 已注册击杀奖励总数。 */
	public int size() {
		return registry.size();
	}

	/** 全部已注册击杀奖励（只读视图）。 */
	public Collection<KillReward> all() {
		return Collections.unmodifiableCollection(registry.values());
	}

	/** 击杀奖励链式构建器：{@code onKill + drop} 组合后 {@code build()} 注册。 */
	public static final class Builder {

		private final String modId;
		private final String name;
		private Consumer<KillContext> onKill;
		private final List<KillReward.KillDrop> drops = new ArrayList<>();

		Builder(String modId, String name) {
			this.modId = modId;
			this.name = name;
		}

		/** 击杀效果回调（可叠加单条；多次调用以后者覆盖前者）。 */
		public Builder onKill(Consumer<KillContext> handler) {
			this.onKill = handler;
			return this;
		}

		/** 击杀掉落：固定数量、必掉。 */
		public Builder drop(ItemLike item, int count) {
			return drop(item, count, count, 1.0f);
		}

		/** 击杀掉落：数量区间、必掉。 */
		public Builder drop(ItemLike item, int min, int max) {
			return drop(item, min, max, 1.0f);
		}

		/** 击杀掉落：数量区间 + 概率 [0,1]。 */
		public Builder drop(ItemLike item, int min, int max, float chance) {
			drops.add(new KillReward.KillDrop(item, min, max, chance));
			return this;
		}

		/** 构建并注册击杀奖励。 */
		public KillReward build() {
			KillReward reward = new KillReward(
				ResourceLocation.fromNamespaceAndPath(modId, name), onKill, drops);
			ChasmKillRewards.INSTANCE.register(modId, name, reward);
			return reward;
		}
	}
}
