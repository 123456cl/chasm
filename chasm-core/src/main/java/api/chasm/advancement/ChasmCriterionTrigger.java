package api.chasm.advancement;

import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import com.mojang.serialization.Codec;

import java.util.function.Predicate;

/**
 * **通用进度触发器**（框架能力）—— {@code CriterionTrigger} 的"数据驱动"实现。
 *
 * <h2>1.21.1 的真实形态（javap 取证）</h2>
 * <pre>
 * public abstract class net.minecraft.advancements.critereon.SimpleCriterionTrigger&lt;T extends SimpleInstance&gt;
 *         implements net.minecraft.advancements.CriterionTrigger&lt;T&gt; {
 *   public final void addPlayerListener(PlayerAdvancements, CriterionTrigger$Listener&lt;T&gt;);
 *   public final void removePlayerListener(...);
 *   public final void removePlayerListeners(PlayerAdvancements);
 *   protected void trigger(ServerPlayer, java.util.function.Predicate&lt;T&gt;);   // &lt;-- protected
 * }
 * public interface net.minecraft.advancements.CriterionTrigger&lt;T&gt; {
 *   com.mojang.serialization.Codec&lt;T&gt; codec();          // 1.21.1 用 codec 取代了 1.20 的 getId/createInstance
 *   default Criterion&lt;T&gt; createCriterion(T);
 * }
 * </pre>
 * <p>1.20 的 {@code AbstractCriterionTriggerInstance} + {@code createInstance(JsonObject, ...)} 在 1.21.1
 * <b>已被删除</b>（javap 报"找不到符号"），条件解析整体改成 DFU codec。</p>
 *
 * <h2>为什么要有这个类</h2>
 * <p>{@code SimpleCriterionTrigger.trigger} 是 {@code protected}，模组无法从外部触发；
 * 而 {@code codec()} 是抽象的，每个触发器都得自己写一遍监听器管理 —— 那些代码逐字相同。
 * 本类把两者收口：<b>一个 id + 一个 codec = 一个可触发、可被数据包引用的触发器</b>。</p>
 *
 * <p>{@link #fire} 内部走的是原版 {@code SimpleCriterionTrigger.trigger}，因此
 * <b>逐字保留原版语义</b>：先按 {@code Predicate} 过滤条件实例，再用
 * {@code EntityPredicate.createContext(player, player)} 校验实例自带的
 * {@code player} 条件（javap {@code SimpleCriterionTrigger.trigger} 字节码实证），
 * 通过后才 {@code Listener.run(PlayerAdvancements)} 授予进度。</p>
 *
 * @param <T> 条件实例类型（须实现 {@link SimpleCriterionTrigger.SimpleInstance}）
 */
public class ChasmCriterionTrigger<T extends SimpleCriterionTrigger.SimpleInstance> extends SimpleCriterionTrigger<T> {

	private final ResourceLocation id;
	private final Codec<T> codec;

	public ChasmCriterionTrigger(ResourceLocation id, Codec<T> codec) {
		this.id = id;
		this.codec = codec;
	}

	@Override
	public Codec<T> codec() {
		return codec;
	}

	/** 注册 id（{@code tetra:block_use} 这种）。 */
	public ResourceLocation id() {
		return id;
	}

	/**
	 * **触发一次**：对所有条件实例求值，命中的授予进度。
	 *
	 * @param player 触发的玩家（null 直接忽略 —— 触发器只在服务端、且有玩家时才有意义）
	 * @param test   条件判定（对每个已注册的条件实例调用一次）
	 */
	public void fire(ServerPlayer player, Predicate<T> test) {
		if (player == null || test == null) {
			return;
		}
		trigger(player, test);
	}

	/** 无条件触发（条件实例自己会在 {@code player} 条件上被原版筛）。 */
	public void fire(ServerPlayer player) {
		fire(player, instance -> true);
	}

	@Override
	public String toString() {
		return "ChasmCriterionTrigger[" + id + "]";
	}
}
