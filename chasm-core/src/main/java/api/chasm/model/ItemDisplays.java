package api.chasm.model;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.item.ItemDisplayContext;

import org.joml.Vector3f;

/**
 * **{@link ItemDisplay}（框架的通用姿态数据）→ 原版 {@code ItemTransforms}（客户端）**。
 *
 * <h2>为什么这层转换单独成类、而且是公开的</h2>
 * <ol>
 *   <li><b>通用/客户端分界</b>：{@link ItemDisplay} 必须能被通用代码（服务端也会加载的
 *       {@link ItemLayerProvider} 实现）构造，所以它不能引用客户端类
 *       （{@code net.minecraft.client.renderer.block.model.*}）；转换只能在客户端做。
 *       把它放在这里，通用侧与渲染侧的分界就只有一处、看得见。</li>
 *   <li><b>可断言</b>：参数顺序这件事光靠"看起来对"是不行的 —— 1.21.1 的 8 参构造器顺序是
 *       {@code thirdPersonLeftHand → thirdPersonRightHand → firstPersonLeftHand →
 *       firstPersonRightHand → head → gui → ground → fixed}（{@code javap -c -p ItemTransforms}
 *       的 8 参构造器按这个次序逐个 {@code putfield}，字节码 offset 5/10/15/20/26/32/38/44），
 *       **既不是字段声明顺序也不是 {@link ItemDisplayContext} 的枚举顺序**。
 *       公开这个方法，测试就能把"框架算出来的变换"与"原版自己解析同一份 JSON 得到的变换"
 *       逐字段比一遍（见 tetra-port 的 {@code TetraShieldDisplayTest}）。</li>
 * </ol>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>{@code null} 或空表 → {@code null}（= "不表态"，调用方应沿用被包模型自己的
 *       {@code getTransforms()}，这样没实现该通道的物品姿态逐字不变）；</li>
 *   <li>非空 → **整表替换**：表里没有的姿态取 {@link ItemTransform#NO_TRANSFORM}。
 *       这正是原版 override 模型的语义（override 自己的 {@code display} 说了算，不与父模型合并）。</li>
 * </ul>
 *
 * <p>{@code Vector3f} 是可变的，而 {@link ItemTransform} 的字段是公开 final 引用 —— 所以这里
 * 逐个 {@code new Vector3f(...)} 拷贝，绝不让框架的数据被渲染侧改到。</p>
 */
@Environment(EnvType.CLIENT)
public final class ItemDisplays {

	private ItemDisplays() {
	}

	/**
	 * {@link ItemDisplay} → 原版 {@link ItemTransforms}。
	 *
	 * @param display 框架的姿态数据；{@code null} / 空 → 返回 {@code null}（不表态）
	 */
	public static ItemTransforms toVanilla(ItemDisplay display) {
		if (display == null || display.isEmpty()) {
			return null;
		}
		return new ItemTransforms(
			of(display.transform(ItemDisplayContext.THIRD_PERSON_LEFT_HAND)),
			of(display.transform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)),
			of(display.transform(ItemDisplayContext.FIRST_PERSON_LEFT_HAND)),
			of(display.transform(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)),
			of(display.transform(ItemDisplayContext.HEAD)),
			of(display.transform(ItemDisplayContext.GUI)),
			of(display.transform(ItemDisplayContext.GROUND)),
			of(display.transform(ItemDisplayContext.FIXED)));
	}

	/** 单个姿态：没有就 {@link ItemTransform#NO_TRANSFORM}（原版语义），有就照数值建（防御性拷贝）。 */
	public static ItemTransform of(ItemDisplay.Transform transform) {
		if (transform == null) {
			return ItemTransform.NO_TRANSFORM;
		}
		return new ItemTransform(
			new Vector3f(transform.rotation()),
			new Vector3f(transform.translation()),
			new Vector3f(transform.scale()));
	}

	/**
	 * **不表态时沿用回退值**（{@link LayeredItemModel} 用；公开出来是为了让"没实现通道的物品
	 * 姿态逐字不变"这条能被单测直接断言）。
	 */
	public static ItemTransforms orElse(ItemDisplay display, ItemTransforms fallback) {
		ItemTransforms converted = toVanilla(display);
		return converted == null ? fallback : converted;
	}
}
