package api.chasm.contrib;

import api.chasm.item.AttributeDeclaration;
import api.chasm.item.event.ItemEventKey;

import net.minecraft.resources.ResourceLocation;

/**
 * 物品贡献来源（**统一管线**）：词缀、宝石、附魔、套装、静态默认……都实现这一个接口。
 *
 * <p>为什么要有它：Apotheosis 那种系统里，"词缀给事件行为 + 给属性加成""宝石给事件行为 + 给属性"
 * 各写一套；chasm 把它合并成**一条管线**——贡献者只声明"我要挂哪些事件处理器、加哪些属性"，
 * 其余（分桶、编译快照、属性物化、存读档一致、批量刷新）全部由
 * {@link ItemContributions} 接管。</p>
 *
 * <p>实现示例：</p>
 * <pre>
 * public final class LifestealAffix implements ItemContributor {
 *     public ResourceLocation id() { return id("mymod", "affix/lifesteal"); }
 *     public void contributeHandlers(HandlerSink sink) {
 *         sink.add(ChasmItemEventKeys.ON_HIT, id("mymod", "lifesteal"));
 *     }
 *     public void contributeAttributes(AttributeSink sink) {
 *         sink.add(AttributeDeclaration.mainHand(Attributes.MAX_HEALTH, id("mymod", "lifesteal_hp"), 4.0F));
 *     }
 * }
 * </pre>
 *
 * <p>所有方法都必须**纯函数式**：同一贡献者在任意时刻返回同样的声明（快照可重算、可复现）。</p>
 */
public interface ItemContributor {

	/** 贡献者唯一 id（注册键，建议 {@code namespace:affix/xxx} 之类分层命名）。 */
	ResourceLocation id();

	/** 声明本贡献者要挂载的**事件处理器**（按事件分桶，运行时零扫描）。 */
	default void contributeHandlers(HandlerSink sink) {
	}

	/** 声明本贡献者提供的**属性加成**（运行时物化进 ATTRIBUTE_MODIFIERS 组件）。 */
	default void contributeAttributes(AttributeSink sink) {
	}

	/**
	 * **按栈参数化**的处理器声明（默认转调 {@link #contributeHandlers(HandlerSink)}）。
	 *
	 * <p>需要它是因为"同一份贡献逻辑，不同实例贡献不同内容"——典型就是**词缀/宝石**：
	 * 词缀的等级与稀有度存在物品栈上，属性值必须按该栈的实例数据算出来。
	 * 框架在编译快照（{@code ItemContributions.rebuild}）时会把栈传进来。</p>
	 */
	default void contributeHandlers(net.minecraft.world.item.ItemStack stack, HandlerSink sink) {
		contributeHandlers(sink);
	}

	/** **按栈参数化**的属性声明（默认转调 {@link #contributeAttributes(AttributeSink)}）。 */
	default void contributeAttributes(net.minecraft.world.item.ItemStack stack, AttributeSink sink) {
		contributeAttributes(sink);
	}

	/**
	 * **镶嵌生命周期钩子**（默认空实现）：宝石被镶进装备时调用。
	 *
	 * <p>为什么需要它：属性型加成只要声明 {@code contributeAttributes} 就会被管线物化，
	 * 但有些加成**必须改写宿主栈本身**——神化的"附魔加成"要把附魔等级并进装备、
	 * "耐久加成"要把最大耐久乘上去。这些不是属性，管线管不了，所以给一个明确钩子。</p>
	 *
	 * <p><b>实现要求</b>：必须**幂等**（同一宿主重复调用结果一致），推荐做法是
	 * "从宿主当前的宝石列表整体重算"，而不是增量叠加——拔出、重载、同步都能安全重放。</p>
	 *
	 * @param host   被镶嵌的装备栈
	 * @param source 触发本次调用的宝石栈（整栈重算时可忽略）
	 */
	default void onSocket(net.minecraft.world.item.ItemStack host, net.minecraft.world.item.ItemStack source) {
	}

	/** **拔出生命周期钩子**（默认空实现）：宝石被拔出后调用，宿主应从"当前宝石列表"重算自身衍生数据。 */
	default void onUnsocket(net.minecraft.world.item.ItemStack host, net.minecraft.world.item.ItemStack source) {
	}

	/** 事件处理器声明出口。 */
	@FunctionalInterface
	interface HandlerSink {
		void add(ItemEventKey<?> event, ResourceLocation handlerId);
	}

	/** 属性声明出口。 */
	@FunctionalInterface
	interface AttributeSink {
		void add(AttributeDeclaration declaration);
	}
}
