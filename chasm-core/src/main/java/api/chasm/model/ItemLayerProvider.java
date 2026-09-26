package api.chasm.model;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * **按物品栈给出外观图层**（由模组实现）。
 *
 * <p>注意这个求值是**按栈**的（同一物品装了不同模块，外观不同），而 baked model 是**按物品**烘焙的 ——
 * 两者的桥接由模型子系统负责（见 {@link ChasmItemLayers} 的说明），实现方只需要"给我一个栈，我还你图层表"。</p>
 *
 * <h2>动画帧 / 状态（可选，默认不表态）</h2>
 * <p>真实模组的模块化物品里有**按动画帧换层**的模型（Tetra 的弓/弩：同一层在
 * {@code undrawn / draw_0 / draw_1 / draw_2 / loaded} 各有一张贴图，
 * 见 {@code _ref/Tetra-1.20/.../module/model/FilteredGridTextureModelData.java:24-26} 与
 * {@code items/modular/impl/bow/ModularBowItem.java:512-536} 的 {@code filterModel}）——
 * 它**必须有"当前帧"这个入参**才能选对层。所以本接口除了 {@link #layersOf(ItemStack)} 之外，
 * 还提供两个**带默认实现**的方法：</p>
 * <ul>
 *   <li>{@link #frameOf(ItemStack, LivingEntity)}：从栈 + 持有者算出当前帧名（默认 {@code ""} = 不表态）；</li>
 *   <li>{@link #layersOf(ItemStack, String)}：按帧求层（默认**忽略帧**，直接转发给无帧版本）。</li>
 * </ul>
 * <p><b>兼容底线</b>：两个方法都有默认实现，所以只写一个 lambda 的旧实现（以及**没有帧概念的一切物品**）
 * 行为逐字不变 —— 帧参数为空串时就是原来的"按栈求层"。</p>
 *
 * <p>实现要求：**永不抛异常、永不返回 null**（渲染线程上抛异常会崩整个客户端）；
 * 拿不到贴图时返回空表或退化层，让框架回落到默认平面贴图。</p>
 */
@FunctionalInterface
public interface ItemLayerProvider {

	/** 该栈的外观图层（按绘制顺序，先画的在下面）。拿不到就返回空表。 */
	List<ItemLayer> layersOf(ItemStack stack);

	/**
	 * **当前动画帧 / 状态名**（默认 {@code ""} = 本物品没有帧概念）。
	 *
	 * <p>调用点在渲染路径上（每帧一次），所以实现必须是 {@code O(1)} 的纯查表/算术，
	 * 不得做全表扫描、不得分配大对象；拿不到（无实体、没在用、数据缺失）就返回 {@code ""}。</p>
	 *
	 * @param stack  物品栈（非 null、非空 —— 调用方已挡）
	 * @param entity 持有者（可为 null：物品栏/GUI 里画的东西没有持有者）
	 */
	default String frameOf(ItemStack stack, LivingEntity entity) {
		return "";
	}

	/**
	 * **按帧求层**（默认忽略帧 → 与 {@link #layersOf(ItemStack)} 逐字一致）。
	 *
	 * @param frame 帧名（{@code ""} = 无帧；实现应当把"无帧"当作自己的静止帧处理，
	 *              这样 {@link #layersOf(ItemStack)} 的语义不会因为引入帧而改变）
	 */
	default List<ItemLayer> layersOf(ItemStack stack, String frame) {
		return layersOf(stack);
	}

	/**
	 * **该状态下的模型姿态变换（{@code display} 通道）**（默认 {@code null} = 本物品不表态）。
	 *
	 * <h2>要解决的问题</h2>
	 * <p>真实模组的模块化物品不只是"多层贴图"，同一个物品在不同状态下还有**不同的姿态变换**：
	 * Tetra 的盾有收起/格挡/投掷三份物品模型，三份的差别**只有 {@code display} 块**
	 * （{@code assets/tetra/models/item/modular_shield.json} 的 {@code overrides} 指向
	 * {@code modular_shield_blocking}/_throwing，真实 {@code ModularShieldItem.java:92-99}
	 * 为它们注册 {@code blocking}/{@code throwing} 两个 {@code ItemProperties} 谓词）。
	 * 只做贴图层、不做变换的话，三态在手上是**同一个姿势**。</p>
	 *
	 * <h2>为什么入参是"帧"</h2>
	 * <p>状态与"动画帧"是同一件事的两种叫法，而且**必须是同一个值**：
	 * {@link #layersOf(ItemStack, String)} 与 {@link #displayOf(ItemStack, String)} 会被
	 * 同一次渲染用**同一个 frame** 调用（见 {@code LayeredItemModel#modelFor}），
	 * 否则会出现"格挡的贴图 + 收起的姿势"这种张冠李戴。
	 * 所以三个方法的帧取值必须自洽：{@link #frameOf} 返回什么，
	 * {@link #layersOf(ItemStack, String)} 与 {@link #displayOf(ItemStack, String)} 就按什么分派。</p>
	 *
	 * <h2>契约</h2>
	 * <ul>
	 *   <li><b>默认返回 {@code null}</b> = 沿用被包模型自己的 {@code getTransforms()} ——
	 *       <b>没有实现这个通道的一切物品，变换与从前逐字一致</b>；</li>
	 *   <li>返回非空时，**整个姿态表被替换**（不与被包模型合并）—— 这正是原版
	 *       {@code ItemTransforms.Deserializer} 对 override 模型的语义：override 模型自己的
	 *       {@code display} 说了算，没有的姿态就是原版 {@code ItemTransform.NO_TRANSFORM}；</li>
	 *   <li>返回的表里缺失的姿态 → 渲染側用 {@code ItemTransform.NO_TRANSFORM}；</li>
	 *   <li>实现必须 **O(1)、不分配、永不抛异常**（渲染每帧都会问一次）；拿不到就返回 {@code null}；
	 *       返回值应当是**稳定的实例**（同状态复用同一个 {@link ItemDisplay} 对象），
	 *       免得每次求值都让模型缓存多一条 —— 框架按内容比较，所以复用只是省事不是必需。</li>
	 * </ul>
	 *
	 * @param stack 物品栈（非 null、非空 —— 调用方已挡）
	 * @param frame 当前帧/状态名（与 {@link #frameOf} 同源；{@code ""} = 无帧）
	 * @return 该状态的姿态变换；{@code null} = 不表态（保持被包模型的变换）
	 */
	default ItemDisplay displayOf(ItemStack stack, String frame) {
		return null;
	}
}
